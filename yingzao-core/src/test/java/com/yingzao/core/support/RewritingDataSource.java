package com.yingzao.core.support;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * F4a 测试方言垫片(仅 test 域):H2 MODE=MySQL 不认 MySQL 的
 * {@code date_sub(now(), interval N minute)} / {@code date_add(now(), interval ? second)},
 * 而这正是事件总线与计划任务的认领/退避谓词原文。本数据源对 Connection 动态代理,
 * 在 createStatement/prepareStatement/prepareCall 前把这两种形状重写为 H2 的
 * {@code dateadd(...)}。生产代码零改动——生产目标是 MySQL,date_sub 本就是正确写法;
 * 本垫片只活在测试 JVM 里(内核 SQL 方言契约由 H2DialectProbeTest 常驻锚定)。
 */
public final class RewritingDataSource implements DataSource {

    private final DataSource target;

    public RewritingDataSource(DataSource target) {
        this.target = target;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(target.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(target.getConnection(username, password));
    }

    /** 两种且仅两种 MySQL 日期函数形状 → H2 dateadd(新形状先在 H2DialectProbeTest 落锚)。 */
    static String rewrite(String sql) {
        sql = sql.replaceAll(
                "(?i)date_sub\\(\\s*now\\(\\)\\s*,\\s*interval\\s+(\\d+)\\s+minute\\)",
                "dateadd(MINUTE, -$1, now())");
        sql = sql.replaceAll(
                "(?i)date_add\\(\\s*now\\(\\)\\s*,\\s*interval\\s+(\\?|\\d+)\\s+second\\)",
                "dateadd(SECOND, $1, now())");
        return sql;
    }

    private static Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                RewritingDataSource.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                handler(connection));
    }

    /**
     * 通用规则:凡首参为 String 的 JDBC 调用一律过 {@link #rewrite}(rewrite 仅在命中
     * date_sub/date_add 形状时改文,setCatalog/setSchema 等非 SQL 字符串天然原样通过);
     * createStatement 无参拿不到 SQL——SQL 在 statement.executeQuery(sql) 才传入,
     * 故返回的 Statement/PreparedStatement 再包一层同规则代理。
     */
    private static InvocationHandler handler(Object target) {
        return (proxy, method, args) -> {
            if (args != null && args.length > 0 && args[0] instanceof String) {
                args = args.clone();
                args[0] = rewrite((String) args[0]);
            }
            try {
                Object out = method.invoke(target, args);
                if (out instanceof Statement stmt && !(out instanceof Proxy)) {
                    return Proxy.newProxyInstance(
                            RewritingDataSource.class.getClassLoader(),
                            interfacesOf(stmt),
                            handler(stmt));
                }
                return out;
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        };
    }

    /** 实现类的全部接口(含父接口/父类接口)——PreparedStatement 也是 Statement,单接口代理会在强转处炸。 */
    private static Class<?>[] interfacesOf(Object target) {
        java.util.Set<Class<?>> set = new java.util.LinkedHashSet<>();
        java.util.Deque<Class<?>> queue = new java.util.ArrayDeque<>();
        queue.add(target.getClass());
        while (!queue.isEmpty()) {
            Class<?> c = queue.poll();
            if (c.isInterface()) {
                set.add(c);
            }
            for (Class<?> i : c.getInterfaces()) {
                if (set.add(i)) {
                    queue.add(i);
                }
            }
            Class<?> sup = c.getSuperclass();
            if (sup != null) {
                queue.add(sup);
            }
        }
        return set.toArray(new Class<?>[0]);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return target.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        target.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        target.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return target.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return target.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return target.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return target.isWrapperFor(iface);
    }
}
