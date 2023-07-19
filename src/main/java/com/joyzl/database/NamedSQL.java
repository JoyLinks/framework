/*
 * www.joyzl.net
 * 中翌智联（重庆）科技有限公司
 * Copyright © JOY-Links Company. All rights reserved.
 */
package com.joyzl.database;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SQL命名参数支持
 * <p>
 * JDBC默认采用索引传递参数，错误率高，编码效率低，不便于阅读排错<br>
 * {@code SELECT * FROM `users` WHERE `id`=?}<br>
 * {@code {CALL demoSp(?, ?)} }<br>
 * {@code Statement.setInt(1,10);}
 * </p>
 * <p>
 * SQL命名参数采用参数名定位参数<br>
 * {@code SELECT * FROM `users` WHERE `id`=?id}<br>
 * {@code {CALL demoSp(?p1, ?p2)} }<br>
 * {@code Statement.setValue("id",10);}<br>
 * 参数名称只能使用 A~Z a~z 01~9 _ 字符
 * </p>
 *
 * @author simon(ZhangXi TEL:13883833982) 2020年3月21日
 *
 */
public final class NamedSQL {

	// 静态集合缓存使用过的NamedSQL
	private final static Map<String, NamedSQL> NAMED_SQL_CACHES = new ConcurrentHashMap<>();

	/**
	 * 获取对象实例，此方法将缓存分析过的SQL语句以提高性能
	 *
	 * @param sql
	 * @return NamedSQL
	 */
	public static NamedSQL get(String sql) {
		NamedSQL named_sql = NAMED_SQL_CACHES.get(sql);
		if (named_sql == null) {
			named_sql = new NamedSQL(sql);
			NAMED_SQL_CACHES.put(sql, named_sql);
		}
		return named_sql;
	}

	/**
	 * 获取所有缓存的NamedSQL实例
	 *
	 * @return {@code  Collection<NamedSQL>}
	 */
	public final static Collection<NamedSQL> select() {
		return NAMED_SQL_CACHES.values();
	}

	/**
	 * 将字符串表示的类型转化为SQL.Types中对应的类型
	 *
	 * @param type
	 * @return 不匹配的类型 返回 Types.OTHER
	 */
	public final static int getType(String type) {
		switch (type.toUpperCase()) {
			case "ARRAY":
				return Types.ARRAY;
			case "BIGINT":
				return Types.BIGINT;
			case "BINARY":
				return Types.BINARY;
			case "BIT":
				return Types.BIT;
			case "BLOB":
				return Types.BLOB;
			case "BOOLEAN":
				return Types.BOOLEAN;
			case "CHAR":
				return Types.CHAR;
			case "CLOB":
				return Types.CLOB;
			case "DATALINK":
				return Types.DATALINK;
			case "DATE":
				return Types.DATE;
			case "DECIMAL":
				return Types.DECIMAL;
			case "DISTINCT":
				return Types.DISTINCT;
			case "DOUBLE":
				return Types.DOUBLE;
			case "FLOAT":
				return Types.FLOAT;
			case "INTEGER":
				return Types.INTEGER;
			case "JAVA_OBJECT":
				return Types.JAVA_OBJECT;
			case "LONGNVARCHAR":
				return Types.LONGNVARCHAR;
			case "LONGVARBINARY":
				return Types.LONGVARBINARY;
			case "LONGVARCHAR":
				return Types.LONGVARCHAR;
			case "NCHAR":
				return Types.NCHAR;
			case "NCLOB":
				return Types.NCLOB;
			case "NULL":
				return Types.NULL;
			case "NUMERIC":
				return Types.NUMERIC;
			case "NVARCHAR":
				return Types.NVARCHAR;
			case "OTHER":
				return Types.OTHER;
			case "REAL":
				return Types.REAL;
			case "REF":
				return Types.REF;
			case "REF_CURSOR":
				return Types.REF_CURSOR;
			case "ROWID":
				return Types.ROWID;
			case "SMALLINT":
				return Types.SMALLINT;
			case "SQLXML":
				return Types.SQLXML;
			case "STRUCT":
				return Types.STRUCT;
			case "TIME":
				return Types.TIME;
			case "TIME_WITH_TIMEZONE":
				return Types.TIME_WITH_TIMEZONE;
			case "TIMESTAMP":
				return Types.TIMESTAMP;
			case "TIMESTAMP_WITH_TIMEZONE":
				return Types.TIMESTAMP_WITH_TIMEZONE;
			case "TINYINT":
				return Types.TINYINT;
			case "VARBINARY":
				return Types.VARBINARY;
			case "VARCHAR":
				return Types.VARCHAR;
			default:
				return Types.OTHER;
		}
	}

	////////////////////////////////////////////////////////////////////////////////

	// 命名SQL
	private final String named;
	// 执行SQL
	private final String execute;
	// SQL命令
	private final String command;
	// 名称集
	final String[] names;
	// 类型集
	final Integer[] types;
	// 是否存储过程/函数
	private final boolean call;

	private NamedSQL(String named_sql) {
		if (named_sql == null) {
			throw new IllegalArgumentException("SQL语句怎么能为空呢???");
		}
		if (named_sql.length() < 3) {
			throw new IllegalArgumentException("SQL语句怎么能这么短呢???");
		}

		// SELECT * FROM table WHERE name = ?key AND email = ?key;
		// {CALL demoSp(?p1, ?p2:INTEGER)}
		// ?name 参数名允许的字符 A~Z a~z 01~9 _,其间不能有空白字符
		// :INTEGER 为注册参数类型,用于返回参数,其间不能有空白字符

		char c;
		List<String> name_list = new ArrayList<String>();
		List<Integer> type_list = new ArrayList<Integer>();
		StringBuilder sql_builder = new StringBuilder();
		StringBuilder name_builder = new StringBuilder();
		for (int index = 0; index < named_sql.length(); index++) {
			c = named_sql.charAt(index);
			sql_builder.append(c);
			if ('?' == c) {
				// 参数名
				while (++index < named_sql.length()) {
					c = named_sql.charAt(index);
					if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || (c >= '0' && c <= '9')) {
						name_builder.append(c);
					} else {
						break;
					}
				}
				name_list.add(name_builder.toString());
				name_builder.setLength(0);

				if (index >= named_sql.length()) {
					// 20200613 如果不判断是否结束,参数的最后一个字符会附加到执行SQL中
					break;
				} else if (':' == c) {
					// 参数类型
					while (++index < named_sql.length()) {
						c = named_sql.charAt(index);
						if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || (c >= '0' && c <= '9')) {
							name_builder.append(c);
						} else {
							sql_builder.append(c);
							break;
						}
					}
					type_list.add(getType(name_builder.toString()));
					name_builder.setLength(0);
				} else {
					type_list.add(null);
					sql_builder.append(c);
				}
			}
		}

		name_builder.setLength(0);
		for (int index = 0; index < sql_builder.length(); index++) {
			c = sql_builder.charAt(index);
			if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
				name_builder.append(c);
			} else {
				// length == 0 说明还未开始命令字母(未开始字母字符)
				if (name_builder.length() > 0) {
					// length > 0 说明命令字母已经结束(已遇到非字母字符)
					break;
				}
			}
		}

		named = named_sql;
		command = name_builder.toString();
		execute = sql_builder.toString();

		names = name_list.toArray(new String[name_list.size()]);
		types = type_list.toArray(new Integer[type_list.size()]);

		// 标记是否存储过程/函数
		call = "CALL".equalsIgnoreCase(command);
	}

	public String[] getNames() {
		return names;
	}

	public Integer[] getTypes() {
		return types;
	}

	/**
	 * 获取是否具有参数
	 *
	 * @return true 有参数 / false 无任何参数
	 */
	public final boolean hasParameters() {
		return hasInParameters() || hasOutParameters();
	}

	/**
	 * 获取是否具有输入参数
	 *
	 * @return true 有参数 / false 无任何输入参数
	 */
	public final boolean hasInParameters() {
		return names != null && names.length > 0;
	}

	/**
	 * 获取是否具有输出参数
	 *
	 * @return true 有参数 / false 无任何参数
	 */
	public final boolean hasOutParameters() {
		return types != null && types.length > 0;
	}

	/**
	 * 获取用户定义的命名SQL
	 *
	 * @return String 不会返回 null
	 */
	public final String getNamedSQL() {
		return named;
	}

	/**
	 * 获取用于JDBC可执行SQL
	 *
	 * @return String 不会返回 null
	 */
	public final String getExcuteSQL() {
		return execute;
	}

	/**
	 * 获取SQL的命令字<br>
	 * <p>
	 * 数据库定义语言(Data Definition Language, DDL)<br>
	 * CREATE / ALTER / DROP <br>
	 * 数据库操作语言(Data Mabipulation Language,DML)<br>
	 * INSERT / UPDATE / DELETE<br>
	 * 数据库查询语言(Data Query Language,DQL)<br>
	 * SELECT<br>
	 * 数据库控制语言(Data Control Language,DCL)<br>
	 * GRANT / REVOKE / COMMIT / ROLLBACK<br>
	 * 存储过程/函数执行语言<br>
	 * CALL
	 * </p>
	 *
	 * @return SQL命令(大写)
	 */
	public final String getSQLCommand() {
		return command;
	}

	/**
	 * 是否存储过程/函数
	 *
	 * @return true / false
	 */
	public final boolean isCall() {
		return call;
	}
}
