package com.yingzao.core;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Locale;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.yingzao.core.support.RewritingDataSource;
import com.yingzao.core.web.BizException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * F4a 内核单测基类(R26~R27):H2 内存库 MODE=MySQL + 单连接数据源。
 *
 * <p>姿势:①单连接(SingleConnectionDataSource)——生产里 last_insert_id() 依赖
 * 「业务事务内同连接」语义,单连接让直接方法调用(无事务代理)获得同等行为;
 * ②库名按测试类唯一 + DB_CLOSE_DELAY=-1,类内建表一次、每用例清表;
 * ③MessageSource 桩原样回显 key——单测只断言错误码,不断言文案(i18n 文案面由
 * 冒烟 enforce 覆盖)。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class KernelTestBase {

    protected static final MessageSource STUB_MESSAGES = new MessageSource() {
        @Override
        public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
            return code;
        }

        @Override
        public String getMessage(String code, Object[] args, Locale locale) {
            return code;
        }

        @Override
        public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
            return resolvable.getDefaultMessage();
        }
    };

    private static final String[] TABLES = {
            "yz_user", "organization", "yz_approval", "yz_approval_record",
            "yz_event_task", "yz_event_consumed", "yz_plan", "yz_audit_log"
    };

    protected DataSource dataSource;
    protected JdbcTemplate jdbc;

    @BeforeAll
    void initDb() throws Exception {
        String url = "jdbc:h2:mem:yz_" + getClass().getSimpleName()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        SingleConnectionDataSource single = new SingleConnectionDataSource(url, true);
        single.setAutoCommit(true);
        // 方言垫片:date_sub/date_add(interval) → H2 dateadd(见垫片类注释)
        dataSource = new RewritingDataSource(single);
        jdbc = new JdbcTemplate(dataSource);
        try (Connection c = single.getConnection()) {
            ScriptUtils.executeSqlScript(c,
                    new EncodedResource(new ClassPathResource("schema.sql"), StandardCharsets.UTF_8));
        }
    }

    @BeforeEach
    void cleanTables() {
        for (String table : TABLES) {
            jdbc.update("delete from " + table);
        }
    }

    /** 断言业务异常及其内核/addon 码段错误码。 */
    protected static BizException expectBiz(int code, Runnable action) {
        BizException ex = assertThrows(BizException.class, action::run);
        assertEquals(code, ex.getCode());
        return ex;
    }
}
