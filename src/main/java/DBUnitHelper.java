import com.google.common.collect.Lists;
import org.dbunit.Assertion;
import org.dbunit.IDatabaseTester;
import org.dbunit.JdbcDatabaseTester;
import org.dbunit.database.DatabaseConfig;
import org.dbunit.database.DatabaseConfig.ConfigProperty;
import org.dbunit.database.IDatabaseConnection;
import org.dbunit.database.QueryDataSet;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.ITable;
import org.dbunit.dataset.filter.DefaultColumnFilter;
import org.dbunit.dataset.xml.FlatXmlDataSet;
import org.dbunit.dataset.xml.FlatXmlDataSetBuilder;
import org.dbunit.operation.DatabaseOperation;
import org.junit.Assert;

import java.io.FileOutputStream;
import java.sql.Statement;
import java.util.List;


public class DBUnitHelper {
    private static IDatabaseTester databaseTester;
    private static IDatabaseConnection connection;
    private static String basePath = "src/test/resources/dataset/";

    /**
     * init com.mogujie.bigdata.DBUnitHelper with h2 connection and self-defined default config.
     * @throws Exception
     */
    public static void init() throws Exception{
        DatabaseConfig defaultConfig = new DatabaseConfig();
        defaultConfig.setProperty(DatabaseConfig.FEATURE_ALLOW_EMPTY_FIELDS, true);
        init(defaultConfig);
    }

    /**
     * init com.mogujie.bigdata.DBUnitHelper with h2 connection and user-defined config.
     * @param config user defined DataBaseConfig
     * @throws Exception
     */
    public static void init(DatabaseConfig config) throws Exception{
        init(InMemDBInitializer.getDb_driver(), InMemDBInitializer.getDb_url(),
                InMemDBInitializer.getDb_user(), InMemDBInitializer.getDb_passwd(), config);
    }

    /**
     * init com.mogujie.bigdata.DBUnitHelper with user-defined connection and system default db config
     * @param driver user define driver
     * @param url user define url
     * @param user user define user
     * @param passwd user define pwsswd
     * @throws Exception
     */
    public static void init(String driver, String url, String user, String passwd) throws Exception {
        init(driver, url, user, passwd, null);
    }

    /**
     * init com.mogujie.bigdata.DBUnitHelper with user-defined connection and user-define config
     * @param driver user define driver
     * @param url user define db url
     * @param user user define db user
     * @param passwd user define db passwd
     * @param config user define database config
     * @throws Exception
     */
    public static void init(String driver, String url, String user, String passwd, DatabaseConfig config) throws Exception {
        databaseTester = new MyDBTester(driver, url, user, passwd, config);
        connection = databaseTester.getConnection();
    }



    public static void setBasePath(String path) {
        basePath = path;
    }

    /**
     * get dbunit connection
     * @return
     */
    public static IDatabaseConnection getConnection() {
        return connection;
    }

    /**
     * execute sql command against your configured database
     * @param sql
     * @throws Exception
     */
    public static void execSql(String sql) throws Exception {
        Statement stmt = connection.getConnection().createStatement();
        try {
            stmt.execute(sql);
        } finally {
            if (stmt != null) {
                stmt.close();
            }
        }
    }

    /**
     * use this method in setUp method, the database tables content will keep same with this dataSet
     * be careful, a dataSet means a collection of tables, suggest use multiple small dataSet.
     * @param fileName the file which contains dataSet init data.
     * @throws Exception
     */
    public static void initDataSet(String fileName) throws Exception {
        IDataSet dataSet = readDataSet(fileName);
        cleanInsert(dataSet);
    }

    /**
     * export dataSet from database to file, this file contains all data/schema of tables listed in tableNames
     * @param tableNames tables should be export
     * @param resultFileName the result file
     * @throws Exception
     */
    public static void exportDataSet(List<String> tableNames, String resultFileName) throws Exception {
        QueryDataSet dataSet = null;
        if (tableNames == null || tableNames.size() == 0) {
            return;
        }
        try {
            dataSet = new QueryDataSet(connection);
            for (String tableName : tableNames) {
                dataSet.addTable(tableName);
            }
        } finally {
            if (dataSet != null) {
                FlatXmlDataSet.write(dataSet, new FileOutputStream(basePath + resultFileName));
            }
        }
    }

    /**
     * export exactly one table to file
     * @param tableName table should be exported
     * @param resultFileName the result file
     * @throws Exception
     */
    public static void exportTable(String tableName, String resultFileName) throws Exception {
        exportDataSet(Lists.newArrayList(tableName), resultFileName);
    }

    /**
     * assert the table content is equal with dataSet, the dataSet table schema should same as tableName.
     * @param tableName table which content should be checked
     * @param expectDataSet expected dataSet
     * @throws Exception
     */
    public static void assertTable(String tableName, IDataSet expectDataSet) throws Exception {
       assertPartialTable(tableName, null, expectDataSet);
    }

    /**
     * assert the table content is equal with dataSet, the dataSet table schema should same as tableName,
     * the schema controlled by sql, the columns this sql return should contain columns in expectDataSet.
     * @param tableName table which content should be checked
     * @param sql sql select subset of table, the columns returned should contain columns in expectDataSet
     * @param expectDataSet the columns can be specified by user
     * @throws Exception
     */
    public static void assertPartialTable(String tableName, String sql, IDataSet expectDataSet) throws Exception {
        QueryDataSet loadedDataSet = new QueryDataSet(connection);
        loadedDataSet.addTable(tableName, sql);
        ITable table1 = loadedDataSet.getTable(tableName);
        ITable table2 = expectDataSet.getTable(tableName);
        Assert.assertEquals(table2.getRowCount(), table1.getRowCount());
        ITable table3 = DefaultColumnFilter.includedColumnsTable(table1, table2.getTableMetaData().getColumns());
        Assertion.assertEquals(table2, table3);
    }



    /**
     * read the DataSet from file
     * @param fileName file contains dataSet data
     * @return DataSet representation form of file
     * @throws Exception
     */
    public static IDataSet readDataSet(String fileName) throws Exception {
        return new FlatXmlDataSetBuilder().build((Thread.currentThread().getContextClassLoader().getResourceAsStream(fileName)));
    }

    /**
     * clean the related database table and make them same as dataSet
     * @param dataSet the dataSet should be init value
     * @throws Exception
     */
    private static void cleanInsert(IDataSet dataSet) throws Exception{
        databaseTester.setDataSet(dataSet);
        databaseTester.setSetUpOperation(DatabaseOperation.CLEAN_INSERT);
        databaseTester.onSetup();
    }

    private static class MyDBTester extends JdbcDatabaseTester {

        private DatabaseConfig config;

        public MyDBTester(String driverClass, String connectionUrl, String username,
                          String password, DatabaseConfig config) throws Exception{
            super(driverClass, connectionUrl, username, password);
            this.config = config;
        }

        @Override
        public IDatabaseConnection getConnection() throws Exception {
            IDatabaseConnection databaseConnection =  super.getConnection();
            DatabaseConfig databaseConfig = databaseConnection.getConfig();
            applyConfig(databaseConfig, config);
            return databaseConnection;
        }

        private void applyConfig(DatabaseConfig config, DatabaseConfig config1) {
            if (config == null || config1 == null) {
                return;
            }
            for (ConfigProperty configProperty : DatabaseConfig.ALL_PROPERTIES) {
                String name = configProperty.getProperty();
                Object value = config1.getProperty(name);
                if ((configProperty.isNullable()) || ((!configProperty.isNullable()) && (value != null))) {
                    config.setProperty(name, value);
                }
            }
        }
    }
}
