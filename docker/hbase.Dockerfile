FROM eclipse-temurin:8-jdk

# ================================================================
# HBase 2.4.17 Standalone 模式 Docker 镜像
# 连接到 ZooKeeper 和 Hadoop HDFS
# ================================================================

ENV HBASE_VERSION=2.4.17
ENV HBASE_HOME=/opt/hbase
ENV PATH=$PATH:$HBASE_HOME/bin

# ---- 安装系统依赖 ----
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    netcat-openbsd \
    && rm -rf /var/lib/apt/lists/*

# ---- 导入预下载的 HBase 二进制包 ----
COPY hbase-${HBASE_VERSION}-bin.tar.gz /tmp/hbase.tar.gz
RUN tar -xzf /tmp/hbase.tar.gz -C /opt/ \
    && mv /opt/hbase-${HBASE_VERSION} /opt/hbase \
    && rm /tmp/hbase.tar.gz

# ---- 配置 hbase-site.xml ----
# ZooKeeper 和 HDFS 地址通过环境变量在运行时可覆盖
RUN echo '<?xml version="1.0" encoding="UTF-8"?>' > $HBASE_HOME/conf/hbase-site.xml \
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

# ---- 配置 JAVA_HOME ----
RUN echo "export JAVA_HOME=\${JAVA_HOME}" >> $HBASE_HOME/conf/hbase-env.sh \
    && echo "export HBASE_MANAGES_ZK=false" >> $HBASE_HOME/conf/hbase-env.sh \
    && echo "export HBASE_LOG_DIR=${HBASE_HOME}/logs" >> $HBASE_HOME/conf/hbase-env.sh

# ---- 创建日志目录 ----
RUN mkdir -p $HBASE_HOME/logs

# ---- 复制启动脚本 ----
COPY entrypoint-hbase.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# ---- 健康检查 ----
HEALTHCHECK --interval=30s --timeout=10s --retries=5 \
    CMD curl -f http://localhost:16010/ || exit 1

EXPOSE 16010 16020 16030

ENTRYPOINT ["/entrypoint.sh"]
