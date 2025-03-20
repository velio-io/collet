### Infer a detailed metadata of images from the rss feed

This pipeline [object-detection-pipeline.edn](object-detection-pipeline.edn) is designed to infer a detailed metadata of
images from the rss feed. First, it will fetch and parse the XML feed itself. Then it will download the HTML content of
each feed item and extract the image URLs from it. Having the image URLs, the pipeline will download the images, save
them into S3 bucket and run the OpenAI model on them to infer the description and keywords for the presented objects.
The resulting metadata will be saved into the Postgres database.

In order to run the pipeline, you need to set the `OPENAI_API_KEY` environment variable to your OpenAI API key.
It's used in the pipeline configuration file [object-detection-config.edn](object-detection-config.edn)

Run the docker-compose file to start required services (Postgres and LocalStack to mimic S3)

```shell
docker-compose up
```

Connect to the Postgres database and create the required tables

```sql
CREATE TABLE product_image_meta
(
    product     TEXT,
    image_path  TEXT,
    description TEXT,
    keywords    TEXT[]
);
```

Run the pipeline

```shell
collet.bb -s object-detection-pipeline.edn -c object-detection-config.edn
```