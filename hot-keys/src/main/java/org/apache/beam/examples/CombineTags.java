package org.apache.beam.examples;

import java.util.Arrays;
import java.util.List;

import com.google.api.services.bigquery.model.TableRow;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.FileIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.Validation.Required;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.transforms.Contextful;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;


public class CombineTags {

    public static interface MyOptions extends PipelineOptions {
        @Required
        @Description("Output GCS path bucket (i.e. gs://BUCKET_NAME/data)")
        String getOutput();
        void setOutput(String s);
    }

    public static class WriteIterableFn extends SimpleFunction<KV<String, Iterable<String>>, String> {
        @Override
        public String apply(KV<String, Iterable<String>> stringIterable) {
            Iterable<String> values = stringIterable.getValue();
            StringBuilder str = new StringBuilder(); 

            for (String value:values) {
                str.append(value + "\n");
            }

            return String.format("Key: %s, Questions: [%s]", stringIterable.getKey(), str);
        }
    }

    public static class TableRowToKV extends DoFn<TableRow, KV<String, String>> {  

        private static final List<String> watchList = Arrays.asList("java", "google-cloud-dataflow", "google-bigquery", "google-cloud-storage",
              "apache-beam", "apache-beam-io", "google-cloud-platform", "google-cloud-pubsub", "spotify-scio");

        @ProcessElement
        public void processElement(ProcessContext c) {
            TableRow row = c.element();
            String[] tags = row.get("tags").toString().split("\\|");
            Boolean emit = false;

            for (String tag:tags) 
            { 
                if (watchList.contains(tag)) {
                    emit = true;
                    break;
                }
            }

            if (emit) {
                Arrays.sort(tags);
                c.output(KV.of(String.join("|", tags), row.toString()));
            }
        }
    }

    @SuppressWarnings("serial")
    public static void main(String[] args) {
        MyOptions options = PipelineOptionsFactory.fromArgs(args).withValidation().as(MyOptions.class);
        Pipeline p = Pipeline.create(options);

        String output = options.getOutput();

        p
            .apply("Read Data", BigQueryIO.read().from("bigquery-public-data:stackoverflow.posts_questions"))
            .apply("Parse + Add Keys", ParDo.of(new TableRowToKV()))
                .setCoder(KvCoder.of(StringUtf8Coder.of(), StringUtf8Coder.of()))
            .apply("Group By Key", GroupByKey.<String, String>create())
            .apply("Write Files", FileIO.<KV<String, Iterable<String>>>write()
                .via(Contextful.fn(new WriteIterableFn()), TextIO.sink())
                .to(output)
                .withNumShards(100));

        p.run();
  }
}                