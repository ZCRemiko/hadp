FROM hadp-hadoop:latest

ENV HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop

# 瑕嗙洊 coresite.xml (鐪熷垎甯冨紡 namenode 鍦板潃) ----
RUN printf '<?xml version="1.0"?>\n<configuration>\n  <property>\n    <name>fs.defaultFS</name>\n    <value>hdfs://namenode:9000</value>\n  </property>\n  <property>\n    <name>hadoop.tmp.dir</name>\n    <value>/opt/hadoop/data/tmp</value>\n  </property>\n</configuration>\n' > $HADOOP_CONF_DIR/core-site.xml

# 瑕嗙洊 hdfssite.xml (鍓湰鏁?2, 鐪熷垎甯冨紡) ----
RUN printf '<?xml version="1.0"?>\n<configuration>\n  <property>\n    <name>dfs.replication</name>\n    <value>2</value>\n  </property>\n  <property>\n    <name>dfs.namenode.name.dir</name>\n    <value>/opt/hadoop/data/name</value>\n  </property>\n  <property>\n    <name>dfs.datanode.data.dir</name>\n    <value>/opt/hadoop/data/data</value>\n  </property>\n  <property>\n    <name>dfs.permissions.enabled</name>\n    <value>false</value>\n  </property>\n  <property>\n    <name>dfs.webhdfs.enabled</name>\n    <value>true</value>\n  </property>\n</configuration>\n' > $HADOOP_CONF_DIR/hdfs-site.xml

# 瑕嗙洊 yarnsite.xml (RM 鍦板潃) ----
RUN printf '<?xml version="1.0"?>\n<configuration>\n  <property>\n    <name>yarn.resourcemanager.hostname</name>\n    <value>resourcemanager</value>\n  </property>\n  <property>\n    <name>yarn.nodemanager.aux-services</name>\n    <value>mapreduce_shuffle</value>\n  </property>\n  <property>\n    <name>yarn.nodemanager.env-whitelist</name>\n    <value>JAVA_HOME,HADOOP_COMMON_HOME,HADOOP_HDFS_HOME,HADOOP_CONF_DIR,CLASSPATH_PREPEND_DISTCACHE,HADOOP_YARN_HOME,HADOOP_MAPRED_HOME</value>\n  </property>\n</configuration>\n' > $HADOOP_CONF_DIR/yarn-site.xml

# 瑕嗙洊 mapredsite.xml ----
RUN printf '<?xml version="1.0"?>\n<configuration>\n  <property>\n    <name>mapreduce.framework.name</name>\n    <value>yarn</value>\n  </property>\n</configuration>\n' > $HADOOP_CONF_DIR/mapred-site.xml

# workers 鏂囦欢 (DataNode 鍒楄〃)
RUN printf "datanode1\ndatanode2\n" > $HADOOP_CONF_DIR/workers
