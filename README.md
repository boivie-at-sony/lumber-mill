# Lumber Mill

*A facility where logs are cut into lumber*

**AWS Focused**
Lumber Mill collects and processes logs similar to logstash but with focus on AWS sources like
S3, Kinesis, Cloudwatch and possiblity to run as AWS Lambda.

**Lumber Mill is under heavy development/refactoring so expect api changes to occur**


## Lambda Cloudwatch Logs to Elasticsearch sample

```groovy
class CloudWatchLogsToKinesisEventProcessor implements EventProcessor {

    Observable<Event> call(Observable observable) {
        observable
        
        .compose (
            new CloudWatchLogsEventPreProcessor()
        )
        
        // Custom grok or other enrich or filtering goes here
        
        .map ( addField('type','cloudwatchlogs'))
        .buffer (100)
        .flatMap (
            AWS.elasticsearch.client (
                url:          'your_aws_elasticsearch_endpoint',
                index_prefix: 'prefix-',
                type:         '{type}',
                region: 'eu-west-1' // Remove region if non AWS elasticsearch 
            )
        )
    }
}


```

## Lambda S3 -> Kinesis sample

Complete sample showing how to download file from S3 and store in Kinesis.

```groovy
class MoveFromS3ToKinesisLambdaEventProcessor implements EventProcessor {

    Observable<Event> call(observable) {

        observable.flatMap (
            s3.download (
                bucket: '{bucket_name}',
                key: '{key}',
                remove: true
            )
        )

       .flatMap (
            gzip.compress (
                file: '{s3_download_path}'
            )
        )

       .flatMap (
            s3.put (
                bucket: '{bucket_name}',
                key: 'processed/{key}.gz',
                file: '{gzip_path_compressed}'
            )
        )
        
        .flatMap(
            file.lines(file: '{s3_download_path}')
        )

        .flatMap (
            grok.parse (
                field:        'message',
                pattern:      '%{AWS_ELB_LOG}',
                tagOnFailure: true
            )
        )

        .map (
            rename (
                from: 'timestamp',
                to  : '@timestamp'
            )
        )
        .map (
            addField ('type', 'elb')
        )
        .buffer(300)
        .flatMap (
            kinesis.bufferedProducer (
                stream:          'your_kinesis_stream_name'
                partition_key:   '{client_ip}',
                max_connections: 10,
                endpoint:        'kinesis.eu-west-1.amazonaws.com')
        )
    }
}

```
## Background
The project started after struggling to make logstash do **exactly** what we wanted to do on AWS and
Lumber Mill is the third generation of this project that we decided to open source. We have been running
earlier versions in production for more than a year.

## Features
We have chosen to limit our first version to the following functionality

* AWS Lambda events from Kinesis, S3 & Cloudwatch Logs
* S3 Get / Put / Delete
* Kinesis PutRecord/s
* Elasticsearch Bulk API client + AWS support for role-based access control
* Limited File operations
* Grok, Gzip, Zlib, Base64, Simple if/then logic
* Pattern extraction of values from events
* Experimental http server (experimenting and unit tests sofar)
