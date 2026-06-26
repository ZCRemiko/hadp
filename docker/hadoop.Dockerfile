FROM eclipse-temurin:8-jdk
# Hadoop 3.2.1 浼垎甯冨紡妯″紡 Docker 闀滃儚
# 鍦ㄥ崟涓鍣ㄤ腑杩愯鍏ㄩ儴 Hadoop 缁勪欢(NameNode+DataNode+ResourceManager+NodeManager)
# 鐢ㄤ簬鏈湴寮€鍙戝拰瀛︿範
ENV HADOOP_VERSION=3.2.1
ENV HADOOP_HOME=/opt/hadoop
ENV HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop
ENV PATH=$PATH:$HADOOP_HOME/bin:$HADOOP_HOME/sbin
# 璁剧疆 Hadoop 杩愯鐢ㄦ埛
ENV HDFS_NAMENODE_USER=root
ENV HDFS_DATANODE_USER=root
ENV HDFS_SECONDARYNAMENODE_USER=root
ENV YARN_RESOURCEMANAGER_USER=root
ENV YARN_NODEMANAGER_USER=root
RUN apt-get update && apt-get install -y --no-install-recommends \
    openssh-server \
    openssh-client \
    curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /var/run/sshd
RUN ssh-keygen -t rsa -P '' -f /root/.ssh/id_rsa \
    && cat /root/.ssh/id_rsa.pub >> /root/.ssh/authorized_keys \
    && chmod 0600 /root/.ssh/authorized_keys \
    && echo "StrictHostKeyChecking no" >> /etc/ssh/ssh_config \
    && echo "UserKnownHostsFile /dev/null" >> /etc/ssh/ssh_config
COPY hadoop-${HADOOP_VERSION}.tar.gz /tmp/hadoop.tar.gz
RUN tar -xzf /tmp/hadoop.tar.gz -C /opt/ \
    && mv /opt/hadoop-${HADOOP_VERSION} /opt/hadoop \
    && rm /tmp/hadoop.tar.gz
RUN mkdir -p /opt/hadoop/data/name \
    /opt/hadoop/data/data \
    /opt/hadoop/data/tmp \
    /opt/hadoop/logs
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
RUN echo '<?xml version="1.0" encoding="UTF-8"?>' > $HADOOP_CONF_DIR/mapred-site.xml \
    && echo '<configuration>' >> $HADOOP_CONF_DIR/mapred-site.xml \
    && echo '  <property>' >> $HADOOP_CONF_DIR/mapred-site.xml \
    && echo '    <name>mapreduce.framework.name</name>' >> $HADOOP_CONF_DIR/mapred-site.xml \
    && echo '    <value>yarn</value>' >> $HADOOP_CONF_DIR/mapred-site.xml \
    && echo '  </property>' >> $HADOOP_CONF_DIR/mapred-site.xml \
    && echo '</configuration>' >> $HADOOP_CONF_DIR/mapred-site.xml
RUN echo "export JAVA_HOME=\${JAVA_HOME}" >> $HADOOP_CONF_DIR/hadoop-env.sh \
    && echo "export HDFS_NAMENODE_USER=root" >> $HADOOP_CONF_DIR/hadoop-env.sh \
    && echo "export HDFS_DATANODE_USER=root" >> $HADOOP_CONF_DIR/hadoop-env.sh \
    && echo "export HDFS_SECONDARYNAMENODE_USER=root" >> $HADOOP_CONF_DIR/hadoop-env.sh \
    && echo "export YARN_RESOURCEMANAGER_USER=root" >> $HADOOP_CONF_DIR/hadoop-env.sh \
    && echo "export YARN_NODEMANAGER_USER=root" >> $HADOOP_CONF_DIR/hadoop-env.sh
COPY entrypoint-hadoop.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD curl -f http://localhost:9870/ || exit 1

EXPOSE 9870 9000 8088 9864 9866

ENTRYPOINT ["/entrypoint.sh"]
