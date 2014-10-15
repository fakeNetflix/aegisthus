/**
 * Copyright 2013 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.netflix.aegisthus.input.AegisthusInputFormat;
import com.netflix.aegisthus.io.writable.AegisthusKey;
import com.netflix.aegisthus.io.writable.AegisthusKeyGroupingComparator;
import com.netflix.aegisthus.io.writable.AegisthusKeyMapper;
import com.netflix.aegisthus.io.writable.AegisthusKeyPartitioner;
import com.netflix.aegisthus.io.writable.AegisthusKeySortingComparator;
import com.netflix.aegisthus.io.writable.AtomWritable;
import com.netflix.aegisthus.io.writable.RowWritable;
import com.netflix.aegisthus.mapreduce.CassSSTableReducer;
import com.netflix.aegisthus.output.CustomFileNameFileOutputFormat;
import com.netflix.aegisthus.output.JsonOutputFormat;
import com.netflix.aegisthus.output.SSTableOutputFormat;
import com.netflix.aegisthus.tools.DirectoryWalker;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public class Aegisthus extends Configured implements Tool {
    private Descriptor.Version version;

    public static void main(String[] args) throws Exception {
        int res = ToolRunner.run(new Configuration(), new Aegisthus(), args);

        boolean exit = Boolean.valueOf(System.getProperty(Feature.CONF_SYSTEM_EXIT, "true"));
        if (exit) {
            System.exit(res);
        } else if (res != 0) {
            throw new RuntimeException("aegisthus finished with a non-zero exit code: " + res);
        }
    }

    private void checkVersionFromFilename(String filename) {
        Descriptor descriptor = Descriptor.fromFilename(filename);

        if (this.version == null) {
            this.version = descriptor.version;
        } else if (!this.version.equals(descriptor.version)) {
            throw new IllegalStateException("All files must have the same sstable version.  File '" + filename
                    + "' has version '" + descriptor.version + "' and we have already seen a file with version '"
                    + version + "'");
        }
    }

    List<Path> getDataFiles(Configuration conf, String dir) throws IOException {
        Set<String> globs = Sets.newHashSet();
        List<Path> output = Lists.newArrayList();
        Path dirPath = new Path(dir);
        FileSystem fs = dirPath.getFileSystem(conf);
        List<FileStatus> input = Lists.newArrayList(fs.listStatus(dirPath));
        for (String path : DirectoryWalker.with(conf).threaded().addAllStatuses(input).pathsString()) {
            if (path.endsWith("-Data.db")) {
                checkVersionFromFilename(path);
                globs.add(path.replaceAll("[^/]+-Data.db", "*-Data.db"));
            }
        }
        for (String path : globs) {
            output.add(new Path(path));
        }
        return output;
    }

    @SuppressWarnings("static-access")
    CommandLine getOptions(String[] args) {
        Options opts = new Options();
        opts.addOption(OptionBuilder.withArgName(Feature.CMD_ARG_INPUT_FILE)
                .withDescription("Each input location")
                .hasArgs()
                .create(Feature.CMD_ARG_INPUT_FILE));
        opts.addOption(OptionBuilder.withArgName(Feature.CMD_ARG_OUTPUT_DIR)
                .isRequired()
                .withDescription("output location")
                .hasArg()
                .create(Feature.CMD_ARG_OUTPUT_DIR));
        opts.addOption(OptionBuilder.withArgName(Feature.CMD_ARG_INPUT_DIR)
                .withDescription("a directory from which we will recursively pull sstables")
                .hasArgs()
                .create(Feature.CMD_ARG_INPUT_DIR));
        opts.addOption(OptionBuilder.withArgName(Feature.CMD_ARG_PRODUCE_SSTABLE)
                .withDescription("produces sstable output (default is to produce json)")
                .create(Feature.CMD_ARG_PRODUCE_SSTABLE));
        CommandLineParser parser = new GnuParser();

        try {
            CommandLine cl = parser.parse(opts, args, true);
            if (!(cl.hasOption(Feature.CMD_ARG_INPUT_FILE) || cl.hasOption(Feature.CMD_ARG_INPUT_DIR))) {
                System.out.println("Must have either an input or inputDir option");
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp(String.format("hadoop jar aegisthus.jar %s", Aegisthus.class.getName()), opts);
                return null;
            }
            return cl;
        } catch (ParseException e) {
            System.out.println("Unexpected exception:" + e.getMessage());
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp(String.format("hadoop jar aegisthus.jar %s", Aegisthus.class.getName()), opts);
            return null;
        }
    }

    @Override
    public int run(String[] args) throws Exception {
        Job job = Job.getInstance(getConf());

        job.setJarByClass(Aegisthus.class);
        CommandLine cl = getOptions(args);
        if (cl == null) {
            return 1;
        }

        // Check all of the paths and load the sstable version from the input filenames
        List<Path> paths = Lists.newArrayList();
        if (cl.hasOption(Feature.CMD_ARG_INPUT_FILE)) {
            for (String input : cl.getOptionValues(Feature.CMD_ARG_INPUT_FILE)) {
                checkVersionFromFilename(input);
                paths.add(new Path(input));
            }
        }
        if (cl.hasOption(Feature.CMD_ARG_INPUT_DIR)) {
            paths.addAll(getDataFiles(job.getConfiguration(), cl.getOptionValue(Feature.CMD_ARG_INPUT_DIR)));
        }

        // At this point we have the version of sstable that we can use for this run
        job.getConfiguration().set(Feature.CONF_SSTABLE_VERSION, version.toString());

        job.setInputFormatClass(AegisthusInputFormat.class);
        job.setMapOutputKeyClass(AegisthusKey.class);
        job.setMapOutputValueClass(AtomWritable.class);
        job.setOutputKeyClass(BytesWritable.class);
        job.setOutputValueClass(RowWritable.class);
        job.setMapperClass(AegisthusKeyMapper.class);
        job.setReducerClass(CassSSTableReducer.class);
        job.setGroupingComparatorClass(AegisthusKeyGroupingComparator.class);
        job.setPartitionerClass(AegisthusKeyPartitioner.class);
        job.setSortComparatorClass(AegisthusKeySortingComparator.class);

        TextInputFormat.setInputPaths(job, paths.toArray(new Path[paths.size()]));

        if (cl.hasOption(Feature.CMD_ARG_PRODUCE_SSTABLE)) {
            job.setOutputFormatClass(SSTableOutputFormat.class);
        } else {
            job.setOutputFormatClass(JsonOutputFormat.class);
        }
        CustomFileNameFileOutputFormat.setOutputPath(job, new Path(cl.getOptionValue(Feature.CMD_ARG_OUTPUT_DIR)));

        job.submit();
        System.out.println(job.getJobID());
        System.out.println(job.getTrackingURL());
        boolean success = job.waitForCompletion(true);
        return success ? 0 : 1;
    }

    public static final class Feature {
        public static final String CMD_ARG_INPUT_DIR = "inputDir";
        public static final String CMD_ARG_INPUT_FILE = "input";
        public static final String CMD_ARG_OUTPUT_DIR = "output";
        public static final String CMD_ARG_PRODUCE_SSTABLE = "produceSSTable";

        /**
         * The column type, used for sorting columns in all output formats and also in the JSON output format. The
         * default is BytesType.
         */
        public static final String CONF_COLUMNTYPE = "aegisthus.columntype";
        /**
         * The converter to use for the column value, used in the JSON output format. The default is BytesType.
         */
        public static final String CONF_COLUMN_VALUE_TYPE = "aegisthus.column_value_type";
        /**
         * Name of the keyspace and dataset to use for the output sstable file name. The default is "keyspace-dataset".
         */
        public static final String CONF_DATASET = "aegisthus.dataset";
        /**
         * The converter to use for the key, used in the JSON output format. The default is BytesType.
         */
        public static final String CONF_KEYTYPE = "aegisthus.keytype";
        /**
         * Should aegisthus try to skip rows with errors.  Defaults to false.  (untested)
         */
        public static final String CONF_SKIP_ROWS_WITH_ERRORS = "aegisthus.skip_rows_with_errors";
        /**
         * The version of SSTable to input and output.
         */
        public static final String CONF_SSTABLE_VERSION = "aegisthus.version_of_sstable";
        /**
         * Configures if the System.exit should be called to end the processing in main.  Defaults to true.
         */
        public static final String CONF_SYSTEM_EXIT = "aegisthus.exit";
    }
}
