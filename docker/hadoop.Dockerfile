FROM eclipse-temurin:8-jdk

# ================================================================
# Hadoop 3.2.1 伪分布式模式 Docker 镜像
# 在单个容器中运行全部 Hadoop 组件(NameNode+DataNode+ResourceManager+NodeManager)
# 用于本地开发和学习
# ================================================================

ENV HADOOP_VERSION=3.2.1
ENV HADOOP_HOME=/opt/hadoop
ENV HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop
ENV PATH=$PATH:$HADOOP_HOME/bin:$HADOOP_HOME/sbin
# 设置 Hadoop 运行用户
ENV HDFS_NAMENODE_USER=root
ENV HDFS_DATANODE_USER=root
ENV HDFS_SECONDARYNAMENODE_USER=root
ENV YARN_RESOURCEMANAGER_USER=root
ENV YARN_NODEMANAGER_USER=root

# ---- 安装系统依赖 (不需要 wget/curl 因为文件已通过 COPY 导入) ----
RUN apt-get update && apt-get install -y --no-install-recommends \
    openssh-server \
    openssh-client \
    curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /var/run/sshd

# ---- 配置 SSH 免密登录 (Hadoop 伪分布式通过 SSH 管理本地守护进程) ----
RUN ssh-keygen -t rsa -P '' -f /root/.ssh/id_rsa \
    && cat /root/.ssh/id_rsa.pub >> /root/.ssh/authorized_keys \
    && chmod 0600 /root/.ssh/authorized_keys \
    && echo "StrictHostKeyChecking no" >> /etc/ssh/ssh_config \
    && echo "UserKnownHostsFile /dev/null" >> /etc/ssh/ssh_config

# ---- 导入预下载的 Hadoop 二进制包 (宿主机下载后 COPY 进来, 避免 Docker 容器内网络受限) ----
COPY hadoop-${HADOOP_VERSION}.tar.gz /tmp/hadoop.tar.gz
RUN tar -xzf /tmp/hadoop.tar.gz -C /opt/ \
    && mv /opt/hadoop-${HADOOP_VERSION} /opt/hadoop \
    && rm /tmp/hadoop.tar.gz

# ---- 创建数据目录 ----
RUN mkdir -p /opt/hadoop/data/name \
    /opt/hadoop/data/data \
    /opt/hadoop/data/tmp \
    /opt/hadoop/logs

# ---- 配置 core-site.xml (HDFS 核心配置) ----
RUN echo '<?xml version="1.0" encoding="UTF-8"?>' > $HADOOP_CONF_DIR/core-site.xml \
    && echo '<?xml-stylesheet type="text/xsl" href="configuration.xsl"?>' >> $HADOOP_CONF_DIR/core-site.xml \
    && echo '<configuration>' >> $HADOOP_CONF_DIR/core-site.xml \
    && echo '  <property>' >> $HADOOP_CONF_DIR/core-site.xml \
    && echo '    <name>fs.defaultFS</name>' >> $HADOOP_CONF_DIR/core-site.xml \
    && echo '    <value>hdfs://hadoop:9000</value>' >> $HADOOP_CONF_DIR/core-site.xml \
    && echo '  </property>' >> $HADOOP_CONF_DIR/core-site.xml \
    && echo '  <property>' >> $HADOOP_CONF_DIR/core-site.xml \
    && echo '    <name>hadoop.tmp.dir</name>' >> $HADOOP_CONF_DIR/core-site.xml \
    && echo '    <value>/opt/hadoop/data/tmp</value>' >> $HADOOP_CONF_DIR/core-site.xml \
    && echo '  </property>' >> $HADOOP_CONF_DIR/core-site.xml \
    && echo '</configuration>' >> $HADOOP_CONF_DIR/core-site.xml

# ---- 配置 hdfs-site.xml (HDFS 副本和存储配置) ----
RUN echo '<?xml version="1.0" encoding="UTF-8"?>' > $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '<configuration>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '  <property>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '    <name>dfs.replication</name>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '    <value>1</value>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '  </property>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '  <property>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '    <name>dfs.namenode.name.dir</name>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '    <value>/opt/hadoop/data/name</value>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '  </property>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '  <property>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '    <name>dfs.datanode.data.dir</name>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '    <value>/opt/hadoop/data/data</value>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '  </property>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '  <property>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '    <name>dfs.permissions.enabled</name>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '    <value>false</value>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '  </property>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '  <property>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '    <name>dfs.webhdfs.enabled</name>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '    <value>true</value>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '  </property>' >> $HADOOP_CONF_DIR/hdfs-site.xml \
    && echo '</configuration>' >> $HADOOP_CONF_DIR/hdfs-site.xml

# ---- 配置 yarn-site.xml (YARN 资源管理配置) ----
RUN echo '<?xml version="1.0" encoding="UTF-8"?>' > $HADOOP_CONF_DIR/yarn-site.xml \
    && echo '<configuration>' >> $HADOOP_CONF_DIR/yarn-site.xml \
    && echo '  <property>' >> $HADOOP_CONF_DIR/yarn-site.xml \
    && echo '    <name>yarn.nodemanager.aux-services</name>' >> $HADOOP_CONF_DIR/yarn-site.xml \
    && echo '    <value>mapreduce_shuffle</value>' >> $HADOOP_CONF_DIR/yarn-site.xml \
    && echo '  </property>' >> $HADOOP_CONF_DIR/yarn-site.xml \
    && echo '  <property>' >> $HADOOP_CONF_DIR/yarn-site.xml \
    && echo '    <name>yarn.nodemanager.env-whitelist</name>' >> $HADOOP_CONF_DIR/yarn-site.xml \
    && echo '    <value>JAVA_HOME,HADOOP_COMMON_HOME,HADOOP_HDFS_HOME,HADOOP_CONF_DIR,CLASSPATH_PREPEND_DISTCACHE,HADOOP_YARN_HOME,HADOOP_MAPRED_HOME</value>' >> $HADOOP_CONF_DIR/yarn-site.xml \
    && echo '  </property>' >> $HADOOP_CONF_DIR/yarn-site.xml \
    && echo '</configuration>' >> $HADOOP_CONF_DIR/yarn-site.xml

# ---- 配置 mapred-site.xml (MapReduce 配置) ----
RUN echo '<?xml version="1.0" encoding="UTF-8"?>' > $HADOOP_CONF_DIR/mapred-site.xml \
    && echo '<configuration>' >> $HADOOP_CONF_DIR/mapred-site.xml \
    && echo '  <property>' >> $HADOOP_CONF_DIR/mapred-site.xml \
    && echo '    <name>mapreduce.framework.name</name>' >> $HADOOP_CONF_DIR/mapred-site.xml \
    && echo '    <value>yarn</value>' >> $HADOOP_CONF_DIR/mapred-site.xml \
    && echo '  </property>' >> $HADOOP_CONF_DIR/mapred-site.xml \
    && echo '</configuration>' >> $HADOOP_CONF_DIR/mapred-site.xml

# ---- 配置 Hadoop 环境变量 ----
RUN echo "export JAVA_HOME=\${JAVA_HOME}" >> $HADOOP_CONF_DIR/hadoop-env.sh \
    && echo "export HDFS_NAMENODE_USER=root" >> $HADOOP_CONF_DIR/hadoop-env.sh \
    && echo "export HDFS_DATANODE_USER=root" >> $HADOOP_CONF_DIR/hadoop-env.sh \
    && echo "export HDFS_SECONDARYNAMENODE_USER=root" >> $HADOOP_CONF_DIR/hadoop-env.sh \
    && echo "export YARN_RESOURCEMANAGER_USER=root" >> $HADOOP_CONF_DIR/hadoop-env.sh \
    && echo "export YARN_NODEMANAGER_USER=root" >> $HADOOP_CONF_DIR/hadoop-env.sh

# ---- 复制启动脚本 ----
COPY entrypoint-hadoop.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# ---- 健康检查 ----
HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD curl -f http://localhost:9870/ || exit 1

EXPOSE 9870 9000 8088 9864 9866

ENTRYPOINT ["/entrypoint.sh"]
