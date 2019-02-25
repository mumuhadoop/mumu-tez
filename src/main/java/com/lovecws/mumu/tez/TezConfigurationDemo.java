package com.lovecws.mumu.tez;

import org.apache.hadoop.conf.Configuration;
import org.apache.tez.dag.api.TezConfiguration;
import org.apache.tez.runtime.library.api.TezRuntimeConfiguration;

public class TezConfigurationDemo {

    public static void main(String[] args) {
        Configuration conf = new Configuration();
        conf.setBoolean(TezConfiguration.TEZ_LOCAL_MODE, true);
        conf.set("fs.default.name", "file:///");
        conf.setBoolean(TezRuntimeConfiguration.TEZ_RUNTIME_OPTIMIZE_LOCAL_FETCH, true);
    }
}
