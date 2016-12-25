package com.mogujie.bigdata;

import org.h2.tools.RunScript;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Properties;

import static org.h2.engine.Constants.UTF8;

/**
 * 蘑菇街 Inc.
 * Copyright (c) 2010-2016 All Rights Reserved.
 * <p>
 * Author: YanXun
 * Date: 16/12/9-上午11:56
 */
public class InMemDBInitializer {

    private static Properties properties = new Properties();
    private static String db_url;
    private static String db_user;
    private static String db_passwd;
    private static String create_table;
    private static String db_driver;

    public static void init() {
        //Ok, in jarvis, the configFileName is "server.properties"
        init("server.properties");
    }

    /**
     * create InmemDB accord to user specified configFileName.
     * @param configFileName
     */
    public static void init(String configFileName) {
        init("dataSource.url", "dataSource.user", "dataSource.password", "initTableSql", "dataSourceClassName", configFileName );
    }

    public static void init(String db_url_key, String db_user_key, String db_passwd_key,
                            String create_table_key, String db_driver_key, String configFileName) {
        try {
            properties.load(Thread.currentThread().getContextClassLoader().getResourceAsStream(configFileName));
            String basePath = Thread.currentThread().getContextClassLoader().getResource("").getPath();
            db_url = properties.getProperty(db_url_key);
            db_user = properties.getProperty(db_user_key);
            db_passwd = properties.getProperty(db_passwd_key);
            create_table = basePath + "/" + properties.getProperty(create_table_key);
            db_driver = properties.getProperty(db_driver_key);
            createTable();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void createTable() {
        try {
            RunScript.execute(db_url, db_user, db_passwd, create_table, UTF8, false);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }




    public static String getDb_driver() {
        return db_driver;
    }

    public static String getDb_url() {
        return db_url;
    }

    public static String getDb_user() {
        return db_user;
    }

    public static String getDb_passwd() {
        return db_passwd;
    }

    public static String getCreate_table() {
        return create_table;
    }
}
