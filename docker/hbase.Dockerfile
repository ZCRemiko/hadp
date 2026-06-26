FROM eclipse-temurin:8-jdk
# HBase 2.4.17 Standalone 妯″紡 Docker 闀滃儚
# 杩炴帴鍒?ZooKeeper 鍜?Hadoop HDFS
ENV HBASE_VERSION=2.4.17
ENV HBASE_HOME=/opt/hbase
ENV PATH=$PATH:$HBASE_HOME/bin
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    netcat-openbsd \
    && rm -rf /var/lib/apt/lists/*
COPY hbase-${HBASE_VERSION}-bin.tar.gz /tmp/hbase.tar.gz
RUN tar -xzf /tmp/hbase.tar.gz -C /opt/ \
    && mv /opt/hbase-${HBASE_VERSION} /opt/hbase \
    && rm /tmp/hbase.tar.gz
# ZooKeeper 鍜?HDFS 鍦板潃閫氳繃鐜鍙橀噺鍦ㄨ繍琛屾椂鍙鐩?RUN echo '<?xml version="1.0" encoding="UTF-8"?>' > $HBASE_HOME/conf/hbase-site.xml \
    && echo '<configuration>' >> $HBASE_HOME/conf/hbase-site.xml \
    && echo '  <property>' >> $HBASE_HOME/conf/hbase-site.xml \
    && echo '    <name>hbase.cluster.distributed</name>' >> $HBASE_HOME/conf/hbase-site.xml \
    && echo '    <value>true</value>' >> $HBASE_HOME/conf/hbase-site.xml \
    && echo '  </property>' >> $HBASE_HOME/conf/hbase-site.xml \
    && echo '  <property>' >> $HBASE_HOME/conf/hbase-site.xml \
    && echo '    <name>hbase.rootdir</name>' >> $HBASE_HOME/conf/hbase-site.xml \
    && echo '    <value>hdfs://hadoop:9000/hbase</value>' >> $HBASE_HOME/conf/hbase-site.xml \
    && echo '  </property>' >> $HBASE_HOME/conf/hbase-site.xml \
    && echo '  <property>' >> $HBASE_HOME/conf/hbase-site.xml \
    && echo '    <name>hbase.zookeeper.quorum</name>' >> $HBASE_HOME/conf/hbase-site.xml \
    && echo '    <value>zookeeper</value>' >> $HBASE_HOME/conf/hbase-site.xml \
    && echo '  </property>' >> $HBASE_HOME/conf/hbase-site.xml \
    && echo '  <property>' >> $HBASE_HOME/conf/hbase-site.xml \
    && echo '    <name>hbase.zookeeper.property.clientPort</name>' >> $HBASE_HOME/conf/hbase-site.xml \
    && echo '    <value>2181</value>' >> $HBASE_HOME/conf/hbase-site.xml \
    && echo '  </property>' >> $HBASE_HOME/conf/hbase-site.xml \
    && echo '</configuration>' >> $HBASE_HOME/conf/hbase-site.xml
RUN echo "export JAVA_HOME=\${JAVA_HOME}" >> $HBASE_HOME/conf/hbase-env.sh \
    && echo "export HBASE_MANAGES_ZK=false" >> $HBASE_HOME/conf/hbase-env.sh \
    && echo "export HBASE_LOG_DIR=${HBASE_HOME}/logs" >> $HBASE_HOME/conf/hbase-env.sh
RUN mkdir -p $HBASE_HOME/logs
COPY entrypoint-hbase.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
HEALTHCHECK --interval=30s --timeout=10s --retries=5 \
    CMD curl -f http://localhost:16010/ || exit 1

EXPOSE 16010 16020 16030

ENTRYPOINT ["/entrypoint.sh"]
