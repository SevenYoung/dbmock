# Database mock
## 背景
工作中，DB是很多项目不可或缺的一部分，单元测试也是项目开发中不可或缺的一个环节，但是当单元测试碰到DB，由于多人协作，网络安全，环境搭建复杂（偷懒）等原因，出现了以下问题：

* 多人共用一个测试DB，导致DB状态不可控
* 单元测试下载不可运行，除非你有测试DB的权限，除非你网络是通的，除非....
* remote读写DB，使得单元测试超慢(说好的时间>10ms就不是单元测试呢？)
* DB状态assert不方便，总不能一条一条读取肉眼看吧，比条数？只适用某些情况。
* 等等

由于这些原因，在上线新版本的时候，估计很多同学都不愿意把单元测试跑一遍，然而很多上线之后出现的问题，是可以通过跑一遍单元测试提前发现的。当项目越来越大的时候，无论是重构还是新功能的添加，都更加依赖测试的完善及其自动化，于是，如果我们能够找到一个方案达到如下目标，工作会变得简单一点：

1. 每次测试运行前，可以指定DB里面的状态
2. DB里面的状态可以很好的探测
3. 每个人有独立的本地的数据库，且每个协作的同学对数据库的视图都是一样的
4. 最好是内存数据库，提升性能
5. 单元测试一处编写，处处下载后无需搭建任何环境即可自动化运行

经过研究，发现可以引入内嵌内存数据库以及DBUnit来达到目标，具体来说，内存数据库可以实现3,4， DBUnit可以解决1,2，结合两者和其他测试工具，5也可以解决。通过实践，已经成功将该解决方案引入了调度系统及其Tesla服务，正好这两个服务一个是用Guice管理依赖，一个是用Spring，后面举例子覆盖面也广一些，除此之外，由于MyBatis底下有DB Driver的抽象，使得所有mapper自动读写内存数据库，一行MyBatis相关的代码都不需要修改。但这不是重点，实践过程中发现这种解决方案具有一定通用性，于是尝试着做了一个工具dbmock，里面只有InMemDBInitializer以及DBUnitHelper类，他们是对内存数据库以及DBUnit的封装，解决了一些实践中的坑，更加便于引入项目使用，分享出来有两个目的：

* 如果能够帮助面临文章开头那些问题的同学，意义会更大
* 工具已经能够满足当前使用，但是可进步的空间很大，大家如果使用得不爽，可以推进工具变得更好

github 地址:

```
https://github.com/SevenYoung/dbmock
```

接下来文章主要阐述两点：

* 工具介绍
	* 内嵌式内存数据库H2
	* DBUnit
	* dbmock
* 工具使用（例子）
	* 环境搭建
	* 初始化内嵌内存数据库
	* 初始化内嵌内存数据库数据集

## 工具介绍
H2和DBunit，官网的说明很详细，只说相关的重点。dbmock的实现会仔细说明，因为这跟使用比较相关。
### 一、内嵌式内存数据H2 （[详见官网](http://www.h2database.com/html/main.html)）
关于H2主要介绍以下三点：
#### 1.使用方式
* Embedde

| JDBC URL           | JDBC连接URL后的效果                     |
|--------------------|-----------------------------------------|
| jdbc:h2:~/test     | 在目录~/下创建test数据库，数据持久化    |
| jdbc:h2:/data/test | 在目录/data下创建test数据库，数据持久化 |

* In-memory

| JDBC URL                                | JDBC连接URL后的效果                                                             |
|------------------------------------|---------------------------------------------------------------------------------|
| jdbc:h2:mem:test                   | 创建内存数据库test，数据不持久化，可以有多个连接，最后一个连接close该数据库销毁 |
| jdbc:h2:mem:                       | 创建无名私有内存数据库，数据不持久化，一个连接用完即释放，数据库销毁            |
| jdbc:h2:mem:test;DB\_CLOSE_DELAY=-1 | 创建内存数据库test，数据不持久化，可以有多个连接，直到jvm退出数据库才销毁       |

* Server mode （单元测试用不到，不讨论）

#### 2.JDBC连接方式
(1)Mybatis/dataSource （比如Druid,Hikari）

| key                 | value                              |
|---------------------|------------------------------------|
| dataSourceClassName | org.h2.jdbcx.JdbcDataSource        |
| dataSource.url      | jdbc:h2:mem:test;DB\_CLOSE_DELAY=-1 |
| dataSource.user     | sa                                 |
| dataSource.password |                                    |

(2)JDBC Driver

```
Class.forName("org.h2.Driver");
Connection conn = DriverManager.getConnection("jdbc:h2:~/test");
```
#### 3.如何创建表
有了连接，创建表很简单，无非就是run sql而已，但是我要说的是run哪些sql，测试数据表的schema需要跟线上同步，但是线上是mysql，线下是h2，会有哪些不同呢？通过实践证明，主要是索引以及存储引擎不同，比如在Sequel pro上面导出调度系统alarm表如下：

```
DROP TABLE IF EXISTS `concurrency_jobtype`;

CREATE TABLE `concurrency_jobtype` (
  `id` int(11) unsigned NOT NULL DEFAULT '0' COMMENT 'id',
  `jobType` varchar(64) NOT NULL DEFAULT '0' COMMENT 'jobType',
  `maxNum` int(11) unsigned NOT NULL DEFAULT '10' COMMENT 'max concurrent number',
  `createTime` datetime NOT NULL COMMENT '创建时间',
  `updateTime` datetime NOT NULL COMMENT '最后更新时间',
  `updateUser` varchar(32) NOT NULL DEFAULT '' COMMENT '更新用户',
  PRIMARY KEY (`id`),
  KEY `index_type` (`jobType`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='Task根据jobType的并发度控制';
```

其中 KEY \`index_type\` (\`jobType`) 与 ENGINE=InnoDB是无法识别的，删除这两个部分即可，对于单元测试来说索引和具体存储引擎，作用不是很大，如果需要考虑这些的话，可以自行调研h2中的相应实现，最保险的做法是用h2 console图形化界面试用sql看是否能够成功创建表，h2 console很简单，[下载](http://www.h2database.com/html/download.html)一个h2（1MB），进入bin/目录下运行

```
java -jar h2*.jar
```

即可，会自动弹出一个web ui，看了之后就知道怎么做了:D，这个工作做一次就好.

### 二、DBUnit（[详见官网](http://dbunit.sourceforge.net/)）
#### 1.介绍
DBUnit是Junit的扩展，主要目标是很方便得将DB控制在一个已知的状态，它只做三件事情.

* DB和xml数据集的数据交换（xml数据集导入DB，DB导出到xml数据集）
* DB状态的Assert（验证数据当前是否与xml数据集相同）
* 测试运行时setup/teardown的多种操作

#### 2.三大核心组件

| 类                  | 描述                         |
|---------------------|------------------------------|
| IDatabaseConnection | 代表DBUnit与DB的一个连接     |
| IDataSet            | 表的集合                     |
| DatabaseOperation   | 一个test运行前后的操作抽象类 |

#### 3.一般使用姿势
1.首先准备一个代表初始数据库snapshot的xml初始数据集

```
<?xml version='1.0' encoding='UTF-8'?>
<dataset>
  <job jobId="1" jobName="job1" jobType="hive" status="1" contentType="1" content="7299" params="{}" submitUser="yanxun" priority="0" isSerial="true" isTemp="false" isKpi="false" appId="5" workerGroupId="1" departmentId="0" bizGroups="" activeStartDate="1970-01-01 08:00:00.0" activeEndDate="9999-01-01 08:00:00.0" expiredTime="-1" failedAttempts="3" failedInterval="30" createTime="2016-09-19 18:37:48.0" updateTime="2016-09-23 14:16:29.0" updateUser="yanxun" recentRecoverTime="2016-11-23 03:11:00.0" maxParallel="10" proxyUser=""/>
  <job_depend jobId="3" preJobId="1" commonStrategy="1" offsetStrategy="cd" createTime="2016-12-01 11:45:58.0" updateTime="2016-12-01 11:45:58.0" updateUser="鸣人"/>
  <job_schedule_expression id="1" jobId="1" expressionType="1" expression="0 0 3 * * ?" createTime="2015-05-18 17:08:44.0" updateTime="2016-02-04 15:13:44.0"/>
  <app appName="jarvis-web" appId="5" appKey="1111" appType="2" status="1" maxConcurrency="10" defaultPriority="10" alarmEnable="true"
       owner="hejian" createTime="2015-09-10 00:00:00" updateTime="2015-12-15 17:11:36" updateUser="hejian" maxResultRows="10000" maxMapperNum="2000"
  />
  <task/>
  <task_history/>
  <task_depend/>
</dataset>
```
上面表示我要初始化我的数据库，初始化成什么样子？通过这个xml数据集，DBUnit会将数据库中job, job\_depend, job\_schedule\_expression, app这四个表各初始化1条记录，task, task\_history, task\_depend表均为空。 初始化可以使用DBUnit的CLEAN\_INSERT组合操作。每次在setup执行CLEAN\_INSERT的话，不需要teardown，数据库永远是这个已知状态，表是在环境初始化就建好的。

2.导出数据

在创建初始xml数据集的时候，我都不是自己手动造的，利用DBUnit的数据导出功能，先将每个表至少导出1条记录，在这个基础上做修改即可，因为修改本地xml文件比起修改remote db要简单得多，很多时候你只需要修改个别字段，很多字段复制之后都不需要改变，个人在使用过程中还是觉得比较方便的。

3.验证数据

可以使用AssertTable这种表级别的Assert方法，可以对比指定表的指定列。

4.Mybatis  

介绍到这里，也许有同学会疑问，DBUnit和Mybatis是如何合作的呢，我做了一张图说明这个问题：

![dbunitMybatis](https://raw.githubusercontent.com/SevenYoung/pictures/master/dbmock/dbunitMybatis.png)

DBUnit只用在三个地方，验证db数据，测试的setup/teardown做操作方便地进行数据库状态初始化，导入导出import/export， 而Mybatis不用管，原来代码怎么用就怎么用，实际上由于Mybatis的mapper很好用，验证db数据的时候也可以直接用Mybatis的操作进行。只需要保证他们使用的配置文件一致，使得他们读写一个h2数据库即可。

### 三、dbmock
在直接使用h2和DBUnit的过程中，我发现有些步骤是可以封装起来的，对我来说，最直观的使用方式是在一个Test Class运行的时候能够很方便地做三件事情：

* BeforeClass的时候启动内存数据库，并且创建好与线上同步的数据表
* Before的时候初始化数据集，使得无论之前run过多少个测试，每个测试启动前，数据库内容是与指定数据集同步的
* 很方便得知道DB里面当前的状态

dbmock的初衷就是做好以上三件事。

#### 1、为了第一件事出现了InMemDBInitialzer.

![InMemDBInitialzer](https://raw.githubusercontent.com/SevenYoung/pictures/master/dbmock/InMemDBInitializer.png)

主要是在配置文件（默认是server.properties）里面读取以下5个属性：

| key                 | example value                              |
|---------------------|------------------------------------|
| dataSourceClassName | org.h2.jdbcx.JdbcDataSource        |
| dataSource.url      | jdbc:h2:mem:test;DB\_CLOSE_DELAY=-1 |
| dataSource.user     | sa                                 |
| dataSource.password | (empty)                                   |
| initTableSql		  | create_table.sql(fileName)         |

其中create\_table.sql是之前已经通过h2 console验证好的建表语句。如果你的配置文件和key与这里一样，那么只要调用InMemDBInitialzer.init()就启动内存数据库，并且创建好与线上同步的数据表，如果配置文件不同，可以使用InMemDBInitialzer.init(String confFileName)，如果key也不同，可以使用InMemDBInitialzer.init(Sting url\_key, String user\_key…, String confFileName)。  
**也就是说只要在BeforeClass中调用InMemDBInitialzer的某个init方法，就可以完成第一件事:启动内存数据库，并且创建好与线上同步的数据表.**

#### 2、为了第二件事出现了DBUnitHelper
![DBHelper](https://raw.githubusercontent.com/SevenYoung/pictures/master/dbmock/DBHelper.png)
只要调用DBUnitHelper.initDataSet(String dataSetFileName)，就会自动将内存数据库中的状态置为与dataSetFileName这个xml数据集同步的状态。为了方便用户构建初始xml数据集，实现了exportTable和exportDataSet方法将线上的表的数据导出到本地指定目录下的xml数据集文件中，修改该数据集即可获得初始化xml数据集.  
**也就是说只要在Before中调用DBUnitHelper的initDataSet方法，就可以完成第二件事：每次测试之前将数据库状态同步为指定的数据集.**


#### 3.为了第三件事还是出现了DBUnitHelper
其实这个问题不是很大，因为Mybatis的读写已经很方便了，但是DBUnitHelper还是提供了AssertTable与assertPartialTable，前者验证整张表，后者验证表的某些列，具体看注释，很多时候通过Mybatis，或者其他方式sensing DB也很方便。

#### 4.解决的坑

1. DBUnit的JDBCTester无法配置，因为这个不是DBUnit的最常用方式，但是Tester的使用方式是最灵活的，这会造成一个问题，DBUnitHelper默认是不允许写空值得，默认是使用DefaultDataTypeFactory，这些在实际中都需要配置，所以写了一个内部辅助类，使得DBUnitHelper可以接收DatabaseConfig参数，解决了这个问题，所以DBUnitHelper也需要init方法来满足不同需求。
2. DBUnit的默认使用方法是继承DBTestCase，这种方式不是很灵活，使用Tester之后，使得这种继承依赖，变成了关联依赖，耦合减轻，使用更加灵活一点。

#### 5.使用姿势
下面是使用dbmock的姿势：

1. 结合Sequel pro和h2 console创建建表语句，假设是create_table.sql，**只用做一次**；
2. 使用DBUnitHelper导出数据库，构建初始xml数据集，**导出动作只要一次**，以后修改都是在本地修改xml文件；
3. 生成配置文件，假设是server.properties，包含dataSource信息和initTableSql的文件名，**只用做一次**；
4. 在BeforeClass里面调用InMemDBInitialzer的init方法和DBUnitHelper的init方法。
5. 在Before里面调用DBUnitHelper的initDataSet。

其中1~3相当于环境搭建，做一次就行， 4~5是每次测试都要做的，**第4步是BeforeClass里面初始化内存数据库，建立与线上同步的表结构，初始化DBUnitHelper；第5步是Before里面每次指定初始化xml数据集**。

## 工具使用

下面步骤的前提是引入jar包，git clone本项目， 打一个fat jar的包，并引入到classpath即可。 具体使用例子因为涉及到公司的项目代码，不便公开说明，有兴趣的可以私聊。 

## 引用

1. [H2官网](http://www.h2database.com/html/main.html)
2. [DBunit官网](http://dbunit.sourceforge.net/)
3. [DBunit一个较好的入门介绍](http://www.onjava.com/pub/a/onjava/2004/01/21/dbunit.html)
4. [Database Tests With Dbunit](http://www.marcphilipp.de/blog/2012/03/13/database-tests-with-dbunit-part-1/)
5. [h2连接自动建表](http://www.h2database.com/html/features.html#execute_sql_on_connection)
6. [Java Embedded Databases Comparison](http://stackoverflow.com/questions/462923/java-embedded-databases-comparison)
7. [Derby vs. H2 vs. HyperSQL](http://db-engines.com/en/system/Derby%3BH2%3BHyperSQL)




















       