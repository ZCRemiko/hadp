FROM hadp-hbase:latest

# Override hbase-site.xml (distributed: 2 RegionServers)
RUN printf '<?xml version="1.0"?>\n<configuration>\n  <property>\n    <name>hbase.cluster.distributed</name>\n    <value>true</value>\n  </property>\n  <property>\n    <name>hbase.rootdir</name>\n    <value>hdfs://namenode:9000/hbase</value>\n  </property>\n  <property>\n    <name>hbase.zookeeper.quorum</name>\n    <value>zookeeper</value>\n  </property>\n  <property>\n    <name>hbase.zookeeper.property.clientPort</name>\n    <value>2181</value>\n  </property>\n</configuration>\n' > $HBASE_HOME/conf/hbase-site.xml

# regionservers file
RUN printf "regionserver1\nregionserver2\n" > $HBASE_HOME/conf/regionservers && touch $HBASE_HOME/conf/backup-masters
