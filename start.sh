#!/bin/bash
cd /Users/haohaop/Documents/nanning-gjj-rag/rag-api
source .env
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
exec java -jar target/rag-api-0.0.1-SNAPSHOT.jar --embedding.url=http://localhost:8002
