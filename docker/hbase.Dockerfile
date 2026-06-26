FROM eclipse-temurin:8-jdk

ENV HBASE_VERSION=2.4.17
ENV HBASE_HOME=/opt/hbase
ENV JAVA_HOME=/usr/local/openjdk-8
ENV PATH=$PATH:$HBASE_HOME/bin

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl netcat-openbsd \
    && rm -rf /var/lib/apt/lists/*

COPY hbase-${HBASE_VERSION}-bin.tar.gz /tmp/hbase.tar.gz
RUN tar -xzf /tmp/hbase.tar.gz -C /opt/ \
    && mv /opt/hbase-${HBASE_VERSION} /opt/hbase \
    && rm /tmp/hbase.tar.gz

RUN printf '<?xml version="1.0"?>\n<configuration>\n  <property>\n    <name>hbase.cluster.distributed</name>\n    <value>true</value>\n  </property>\n  <property>\n    <name>hbase.rootdir</name>\n    <value>hdfs://hadoop:9000/hbase</value>\n  </property>\n  <property>\n    <name>hbase.zookeeper.quorum</name>\n    <value>zookeeper</value>\n  </property>\n  <property>\n    <name>hbase.zookeeper.property.clientPort</name>\n    <value>2181</value>\n  </property>\n</configuration>\n' > $HBASE_HOME/conf/hbase-site.xml

RUN echo "export JAVA_HOME=\${JAVA_HOME}" >> $HBASE_HOME/conf/hbase-env.sh \
    && echo "export HBASE_MANAGES_ZK=false" >> $HBASE_HOME/conf/hbase-env.sh \
    && echo "export HBASE_LOG_DIR=\${HBASE_HOME}/logs" >> $HBASE_HOME/conf/hbase-env.sh

RUN mkdir -p $HBASE_HOME/logs

COPY entrypoint-hbase.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

HEALTHCHECK --interval=30s --timeout=10s --retries=5 \
    CMD curl -f http://localhost:16010/ || exit 1

EXPOSE 16010 16020 16030

ENTRYPOINT ["/entrypoint.sh"]
