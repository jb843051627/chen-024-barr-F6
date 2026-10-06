package com.fc.v2;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

/**
 * 单测里把 MyBatis-Plus QueryWrapper 翻成内存条件：不连库，照 eq / isNull / lt / in 扫内存行。
 * 只覆盖卷宗流转这几处实际用到的写法。
 */
final class WrapperConds {

    private static final Pattern EQ =
            Pattern.compile("([a-z_]+)\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}");
    private static final Pattern LT =
            Pattern.compile("([a-z_]+)\\s*<\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}");
    private static final Pattern IS_NULL = Pattern.compile("([a-z_]+)\\s+IS\\s+NULL", Pattern.CASE_INSENSITIVE);
    private static final Pattern IN =
            Pattern.compile("([a-z_]+)\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REF = Pattern.compile("#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}");

    final Map<String, Object> eq = new LinkedHashMap<>();
    final Map<String, Object> lt = new LinkedHashMap<>();
    final Map<String, List<Object>> in = new LinkedHashMap<>();
    final List<String> isNull = new ArrayList<>();

    private WrapperConds() {
    }

    static WrapperConds of(QueryWrapper<?> w) {
        WrapperConds c = new WrapperConds();
        if (w == null) {
            return c;
        }
        Map<?, ?> pairs = w.getParamNameValuePairs();
        String seg = w.getSqlSegment() == null ? "" : w.getSqlSegment();

        Matcher m = EQ.matcher(seg);
        while (m.find()) {
            c.eq.put(m.group(1), pairs.get(m.group(2)));
        }
        m = LT.matcher(seg);
        while (m.find()) {
            c.lt.put(m.group(1), pairs.get(m.group(2)));
        }
        m = IS_NULL.matcher(seg);
        while (m.find()) {
            if (!c.isNull.contains(m.group(1))) {
                c.isNull.add(m.group(1));
            }
        }
        m = IN.matcher(seg);
        while (m.find()) {
            List<Object> vals = new ArrayList<>();
            Matcher r = REF.matcher(m.group(2));
            while (r.find()) {
                vals.add(pairs.get(r.group(1)));
            }
            c.in.put(m.group(1), vals);
        }
        return c;
    }

    /** 内存行是否满足条件（列名按下划线转属性 getter） */
    static boolean match(Object bean, WrapperConds c) {
        for (Map.Entry<String, Object> e : c.eq.entrySet()) {
            Object v = read(bean, e.getKey());
            if (v == null || !v.equals(e.getValue())) {
                return false;
            }
        }
        for (String col : c.isNull) {
            if (read(bean, col) != null) {
                return false;
            }
        }
        for (Map.Entry<String, Object> e : c.lt.entrySet()) {
            Object v = read(bean, e.getKey());
            if (v == null || ((Number) v).intValue() >= ((Number) e.getValue()).intValue()) {
                return false;
            }
        }
        for (Map.Entry<String, List<Object>> e : c.in.entrySet()) {
            Object v = read(bean, e.getKey());
            if (!e.getValue().contains(v)) {
                return false;
            }
        }
        return true;
    }

    private static Object read(Object bean, String column) {
        StringBuilder prop = new StringBuilder();
        for (String p : column.split("_")) {
            prop.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        try {
            Method g = bean.getClass().getMethod("get" + prop);
            return g.invoke(bean);
        } catch (Exception e) {
            return null;
        }
    }
}

