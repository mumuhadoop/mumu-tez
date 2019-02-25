package com.lovecws.mumu.tez.wordcount;

import com.google.common.base.Preconditions;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.tez.client.TezClient;
import org.apache.tez.dag.api.*;
import org.apache.tez.dag.api.client.DAGClient;
import org.apache.tez.dag.api.client.DAGStatus;
import org.apache.tez.mapreduce.input.MRInput;
import org.apache.tez.mapreduce.output.MROutput;
import org.apache.tez.mapreduce.processor.SimpleMRProcessor;
import org.apache.tez.runtime.api.ProcessorContext;
import org.apache.tez.runtime.library.api.KeyValueReader;
import org.apache.tez.runtime.library.api.KeyValueWriter;
import org.apache.tez.runtime.library.api.KeyValuesReader;
import org.apache.tez.runtime.library.api.TezRuntimeConfiguration;
import org.apache.tez.runtime.library.conf.OrderedPartitionedKVEdgeConfig;
import org.apache.tez.runtime.library.partitioner.HashPartitioner;
import org.apache.tez.runtime.library.processor.SimpleProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.StringTokenizer;


public class WCount extends Configured implements Tool {
    static String INPUT = "Input";
    static String OUTPUT = "Output";
    static String TOKENIZER = "Tokenizer";
    static String SUMMATION = "Summation";
    private static final Logger LOG = LoggerFactory.getLogger(WCount.class);

    /*
     * Processors typically apply the main application logic to the data.
     * TokenProcessor tokenizes the input data.
     * It uses an input that provide a Key-Value reader and writes
     * output to a Key-Value writer. The processor inherits from SimpleProcessor
     * since it does not need to handle any advanced constructs for Processors.
     */
    public static class TokenProcessor extends SimpleProcessor {
        IntWritable one = new IntWritable(1);
        Text word = new Text();

        public TokenProcessor(ProcessorContext context) {
            super(context);
        }

        @Override
        public void run() throws Exception {
            Preconditions.checkArgument(getInputs().size() == 1);
            Preconditions.checkArgument(getOutputs().size() == 1);
            // the recommended approach is to cast the reader/writer to a specific type instead
            // of casting the input/output. This allows the actual input/output type to be replaced
            // without affecting the semantic guarantees of the data type that are represented by
            // the reader and writer.
            // The inputs/outputs are referenced via the names assigned in the DAG.
            KeyValueReader kvReader = (KeyValueReader) getInputs().get(INPUT).getReader();
            KeyValueWriter kvWriter = (KeyValueWriter) getOutputs().get(SUMMATION).getWriter();
            while (kvReader.next()) {
                StringTokenizer itr = new StringTokenizer(kvReader.getCurrentValue().toString());
                while (itr.hasMoreTokens()) {
                    word.set(itr.nextToken());
                    // Count 1 every time a word is observed. Word is the key a 1 is the value
                    kvWriter.write(word, one);
                }
            }
        }

    }

    /*
     * Example code to write a processor that commits final output to a data sink
     * The SumProcessor aggregates the sum of individual word counts generated by
     * the TokenProcessor.
     * The SumProcessor is connected to a DataSink. In this case, its an Output that
     * writes the data via an OutputFormat to a data sink (typically HDFS). Thats why
     * it derives from SimpleMRProcessor that takes care of handling the necessary
     * output commit operations that makes the final output available for consumers.
     */
    public static class SumProcessor extends SimpleMRProcessor {
        public SumProcessor(ProcessorContext context) {
            super(context);
        }

        @Override
        public void run() throws Exception {
            Preconditions.checkArgument(getInputs().size() == 1);
            Preconditions.checkArgument(getOutputs().size() == 1);
            KeyValueWriter kvWriter = (KeyValueWriter) getOutputs().get(OUTPUT).getWriter();
            // The KeyValues reader provides all values for a given key. The aggregation of values per key
            // is done by the LogicalInput. Since the key is the word and the values are its counts in
            // the different TokenProcessors, summing all values per key provides the sum for that word.
            KeyValuesReader kvReader = (KeyValuesReader) getInputs().get(TOKENIZER).getReader();
            while (kvReader.next()) {
                Text word = (Text) kvReader.getCurrentKey();
                int sum = 0;
                for (Object value : kvReader.getCurrentValues()) {
                    sum += ((IntWritable) value).get();
                }
                kvWriter.write(word, new IntWritable(sum));
            }
            // deriving from SimpleMRProcessor takes care of committing the output
            // It automatically invokes the commit logic for the OutputFormat if necessary.
        }
    }

    private DAG createDAG(TezConfiguration tezConf, String inputPath, String outputPath,
                          int numPartitions) throws IOException {

        // Create the descriptor that describes the input data to Tez. Using MRInput to read text
        // data from the given input path. The TextInputFormat is used to read the text data.
        DataSourceDescriptor dataSource = MRInput.createConfigBuilder(new Configuration(tezConf),
                TextInputFormat.class, inputPath).build();

        // Create a descriptor that describes the output data to Tez. Using MROoutput to write text
        // data to the given output path. The TextOutputFormat is used to write the text data.
        DataSinkDescriptor dataSink = MROutput.createConfigBuilder(new Configuration(tezConf),
                TextOutputFormat.class, outputPath).build();

        // Create a vertex that reads the data from the data source and tokenizes it using the
        // TokenProcessor. The number of tasks that will do the work for this vertex will be decided
        // using the information provided by the data source descriptor.
        Vertex tokenizerVertex = Vertex.create(TOKENIZER, ProcessorDescriptor.create(
                TokenProcessor.class.getName())).addDataSource(INPUT, dataSource);

        // Create the edge that represents the movement and semantics of data between the producer
        // Tokenizer vertex and the consumer Summation vertex. In order to perform the summation in
        // parallel the tokenized data will be partitioned by word such that a given word goes to the
        // same partition. The counts for the words should be grouped together per word. To achieve this
        // we can use an edge that contains an input/output pair that handles partitioning and grouping
        // of key value data. We use the helper OrderedPartitionedKVEdgeConfig to create such an
        // edge. Internally, it sets up matching Tez inputs and outputs that can perform this logic.
        // We specify the key, value and partitioner type. Here the key type is Text (for word), the
        // value type is IntWritable (for count) and we using a hash based partitioner. This is a helper
        // object. The edge can be configured by configuring the input, output etc individually without
        // using this helper. The setFromConfiguration call is optional and allows overriding the config
        // options with command line parameters.
        OrderedPartitionedKVEdgeConfig edgeConf = OrderedPartitionedKVEdgeConfig
                .newBuilder(Text.class.getName(), IntWritable.class.getName(),
                        HashPartitioner.class.getName())
                .setFromConfiguration(tezConf)
                .build();

        // Create a vertex that reads the tokenized data and calculates the sum using the SumProcessor.
        // The number of tasks that do the work of this vertex depends on the number of partitions used
        // to distribute the sum processing. In this case, its been made configurable via the
        // numPartitions parameter.
        Vertex summationVertex = Vertex.create(SUMMATION,
                ProcessorDescriptor.create(SumProcessor.class.getName()), numPartitions)
                .addDataSink(OUTPUT, dataSink);

        // No need to add jar containing this class as assumed to be part of the Tez jars. Otherwise
        // we would have to add the jars for this code as local files to the vertices.

        // Create DAG and add the vertices. Connect the producer and consumer vertices via the edge
        DAG dag = DAG.create("WCount");
        dag.addVertex(tokenizerVertex)
                .addVertex(summationVertex)
                .addEdge(
                        Edge.create(tokenizerVertex, summationVertex, edgeConf.createDefaultEdgeProperty()));
        return dag;
    }

    public boolean run(String inputPath, String outputPath, Configuration conf,
                       int numPartitions) throws Exception {
        LOG.info("Running WCount");
        TezConfiguration tezConf;
        if (conf != null) {
            tezConf = new TezConfiguration(conf);
        } else {
            tezConf = new TezConfiguration();
        }

        tezConf.setBoolean(TezConfiguration.TEZ_LOCAL_MODE, true);
        tezConf.set("fs.defaultFS", "file:///");
        tezConf.setBoolean(TezRuntimeConfiguration.TEZ_RUNTIME_OPTIMIZE_LOCAL_FETCH, true);

//    tezConf.set("yarn.resourcemanager.hostname", "master");
//    tezConf.set("fs.defaultFS", "hdfs:///");

        UserGroupInformation.setConfiguration(tezConf);

        // Create the TezClient to submit the DAG. Pass the tezConf that has all necessary global and
        // dag specific configurations

        tezConf.set(TezConfiguration.TEZ_AM_STAGING_DIR, "d:/tmp/tez/");

        //tezConf.set("tez.staging-dir","D:///tez/temp");
        TezClient tezClient = TezClient.create("WCount", tezConf);
        // TezClient must be started before it can be used
        tezClient.start();

        try {
            DAG dag = createDAG(tezConf, inputPath, outputPath, numPartitions);

            // check that the execution environment is ready
            tezClient.waitTillReady();
            // submit the dag and receive a dag client to monitor the progress
            DAGClient dagClient = tezClient.submitDAG(dag);

            // monitor the progress and wait for completion. This method blocks until the dag is done.
            DAGStatus dagStatus = dagClient.waitForCompletionWithStatusUpdates(null);
            // check success or failure and print diagnostics
            if (dagStatus.getState() != DAGStatus.State.SUCCEEDED) {
                LOG.error("DAG diagnostics: " + dagStatus.getDiagnostics());
                return false;
            }
            return true;
        } finally {
            // stop the client to perform cleanup
            tezClient.stop();
        }
    }

    @Override
    public int run(String[] args) throws Exception {
        Configuration conf = getConf();

        //hdfs path: {"hdfs://master:8020/tmp/input/", "hdfs://master:8020/tmp/output/"};
        String inputPath = "i:/data/sina/1534431734482.json";
        String outputPath = "i:/data/sina/tez";
        int numPartitions = 1;
        WCount job = new WCount();
        LOG.info("Input path: " + inputPath + ", Output path: " + outputPath);

        if (job.run(inputPath, outputPath, conf, numPartitions)) {
            return 0;
        }
        return 1;
    }

    public static void main(String[] args) throws Exception {
        int res = ToolRunner.run(new Configuration(), new WCount(), args);
        System.exit(res);
    }
}
