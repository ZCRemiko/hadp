FROM eclipse-temurin:8-jdk

ENV HADOOP_VERSION=3.2.1
ENV HADOOP_HOME=/opt/hadoop
ENV HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop
ENV PATH=$PATH:$HADOOP_HOME/bin:$HADOOP_HOME/sbin
ENV HDFS_NAMENODE_USER=root
ENV HDFS_DATANODE_USER=root
ENV HDFS_SECONDARYNAMENODE_USER=root
ENV YARN_RESOURCEMANAGER_USER=root
ENV YARN_NODEMANAGER_USER=root

RUN apt-get update && apt-get install -y --no-install-recommends \
    openssh-server openssh-client curl \
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

RUN mkdir -p /opt/hadoop/data/name /opt/hadoop/data/data /opt/hadoop/data/tmp /opt/hadoop/logs

RUN printf '<?xml version="1.0"?>\n<configuration>\n  <property>\n    <name>fs.defaultFS</name>\n    <value>hdfs://hadoop:9000</value>\n  </property>\n  <property>\n    <name>hadoop.tmp.dir</name>\n    <value>/opt/hadoop/data/tmp</value>\n  </property>\n</configuration>\n' > $HADOOP_CONF_DIR/core-site.xml

RUN printf '<?xml version="1.0"?>\n<configuration>\n  <property>\n    <name>dfs.replication</name>\n    <value>1</value>\n  </property>\n  <property>\n    <name>dfs.namenode.name.dir</name>\n    <value>/opt/hadoop/data/name</value>\n  </property>\n  <property>\n    <name>dfs.datanode.data.dir</name>\n    <value>/opt/hadoop/data/data</value>\n  </property>\n  <property>\n    <name>dfs.permissions.enabled</name>\n    <value>false</value>\n  </property>\n  <property>\n    <name>dfs.webhdfs.enabled</name>\n    <value>true</value>\n  </property>\n</configuration>\n' > $HADOOP_CONF_DIR/hdfs-site.xml

RUN printf '<?xml version="1.0"?>\n<configuration>\n  <property>\n    <name>yarn.nodemanager.aux-services</name>\n    <value>mapreduce_shuffle</value>\n  </property>\n  <property>\n    <name>yarn.nodemanager.env-whitelist</name>\n    <value>JAVA_HOME,HADOOP_COMMON_HOME,HADOOP_HDFS_HOME,HADOOP_CONF_DIR,CLASSPATH_PREPEND_DISTCACHE,HADOOP_YARN_HOME,HADOOP_MAPRED_HOME</value>\n  </property>\n</configuration>\n' > $HADOOP_CONF_DIR/yarn-site.xml

RUN printf '<?xml version="1.0"?>\n<configuration>\n  <property>\n    <name>mapreduce.framework.name</name>\n    <value>yarn</value>\n  </property>\n</configuration>\n' > $HADOOP_CONF_DIR/mapred-site.xml

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
