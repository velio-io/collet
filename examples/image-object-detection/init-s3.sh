#!/bin/bash

echo "Creating S3 bucket"
awslocal s3 mb s3://product-images
echo "S3 bucket created successfully"