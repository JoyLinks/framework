/*
 * www.joyzl.net
 * 中翌智联（重庆）科技有限公司
 * Copyright © JOY-Links Company. All rights reserved.
 */
package com.joyzl.database;

import java.io.Closeable;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 数据库操作状态对象
 *
 * @author simon(ZhangXi TEL:13883833982) 2020年3月21日
 *
 */
public class Statement implements Closeable {

	private final NamedSQL namedsql;
	private final PreparedStatement statement;
	private ResultSet result;
	private int[] results;
	private boolean batch;
	private boolean error;

	// 事务子对象,
	private boolean share;

	/**
	 * 初始化数据库操作状态对象
	 *
	 * @param sql 命名参数SQL
	 * @param transaction 是否开启事务
	 */
	public Statement(String sql, boolean transaction) {
		namedsql = NamedSQL.get(sql);
		try {
			final Connection connection = Database.getConnection();
			// 注意区分当前的transaction和Statement.transaction成员
			// 参数用于指示时候开启数据库链路的事务
			// Statement.transaction用于标记子对象具有事务，以便子对象释放时不会意外关闭/回收数据库链路
			connection.setAutoCommit(!transaction);

			if (namedsql.isCall()) {
				statement = connection.prepareCall(namedsql.getExcuteSQL());
			} else {
				statement = connection.prepareStatement(namedsql.getExcuteSQL(), java.sql.Statement.RETURN_GENERATED_KEYS);
			}
		} catch (SQLException e) {
			error = true;
			throw new RuntimeException(e);
		}
	}

	/**
	 * 初始化数据库操作状态对象
	 *
	 * @param sql 命名参数SQL
	 * @param statement 关联的 {@link Statement} 如果开启了事务新的 {@link Statement}
	 *            也将开启事务。
	 */
	public Statement(String sql, Statement statement) {
		namedsql = NamedSQL.get(sql);
		try {
			final Connection connection = statement.statement.getConnection();
			if (namedsql.isCall()) {
				this.statement = connection.prepareCall(namedsql.getExcuteSQL());
			} else {
				this.statement = connection.prepareStatement(namedsql.getExcuteSQL(), java.sql.Statement.RETURN_GENERATED_KEYS);
			}

			// 事务状态由connection.getAutoCommit()标识
			// share表示此数据库链路有多个对象使用
			share = true;
		} catch (SQLException e) {
			error = true;
			throw new RuntimeException(e);
		}
	}

	/**
	 * 添加一次批处理队列<br>
	 * 必须启用事务，只能执行 UPDATE / INSERT / DELETE
	 */
	public final void batch() {
		try {
			statement.addBatch();
			batch = true;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
		// statement.executeBatch();
		// statement.clearBatch();
	}

	/**
	 * 请求数据库执行SQL
	 *
	 * @return true /false 执行成功/执行失败
	 */
	public final boolean execute() {
		try {
			if (result != null) {
				// 多次执行时自动关闭上一次的结果集
				result.close();
				result = null;
			}
			if (batch) {
				results = statement.executeBatch();
				// 批量处理时无须对每个执行的影响数量进行判断
				return results != null && results.length > 0;
			} else {
				if (namedsql.isCall()) {
					// 注册输出参数
					CallableStatement callable = (CallableStatement) statement;
					try {
						for (int index = 0; index < namedsql.types.length; index++) {
							if (namedsql.types[index] != null) {
								callable.registerOutParameter(index + 1, namedsql.types[index]);
							}
						}
					} catch (SQLException ex) {
						throw new RuntimeException(ex);
					}
				}
				// execute()只在第一个返回为结果集的时候为真
				if (statement.execute()) {
					return true;
				} else {
					return statement.getUpdateCount() > 0;
				}
			}
		} catch (Exception ex) {
			error = true;
			try {
				if (!statement.getConnection().getAutoCommit()) {
					// 如果禁用了自动提交则执行回滚
					statement.getConnection().rollback();
				}
			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 获取执行SQL后更新的记录数量
	 *
	 * @return 0 没有记录被更新 / 1~n 更新的记录数 / -1 如果执行的是查询
	 */
	public final int getUpdatedCount() {
		if (batch) {
			if (results == null) {
				return 0;
			}
			int count = 0;
			for (int index = 0; index < results.length; index++) {
				count += results[index];
			}
			return count;
		} else {
			try {
				return statement.getUpdateCount();
			} catch (SQLException ex) {
				error = true;
				throw new RuntimeException(ex);
			}
		}
	}

	/**
	 * 获取执行批量SQL后更新的记录数量
	 * 
	 * @return int[] 按批量执行顺序返回受影响行数 / null 如果未执行过批量处理
	 */
	public final int[] getUpdatedBatchs() {
		return results;
	}

	/**
	 * 如果执行插入，则移动到下一条记录的自动ID
	 *
	 * @return 有ID可读 true / false 没有ID可读
	 */
	public final boolean nextAutoId() {
		try {
			if (result == null) {
				result = statement.getGeneratedKeys();
				if (result == null) {
					return false;
				}
			}
			if (result.next()) {
				return true;
			} else {
				result.close();
				result = null;
				return false;
			}
		} catch (SQLException ex) {
			error = true;
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 获取创建新记录时数据库生成的记录ID
	 *
	 * @return 只有具有自增id特性的数据插入操作才会返回有效id / 0 未返回有效id
	 */
	public final int getAutoId() {
		try {
			if (result == null) {
				result = statement.getGeneratedKeys();
				if (result == null) {
					return 0;
				}
				if (result.next()) {
					return result.getInt(1);
				}
			} else {
				return result.getInt(1);
			}
		} catch (SQLException ex) {
			error = true;
			throw new RuntimeException(ex);
		}
		return 0;
	}

	/**
	 * 如果执行查询，则移动到下一条记录
	 *
	 * @return 有记录可读 true / false 没有记录可读
	 */
	public final boolean nextRecord() {
		try {
			if (result == null) {
				result = statement.getResultSet();
				if (result == null) {
					return false;
				}
			}
			if (result.next()) {
				return true;
			} else {
				result.close();
				result = null;
				return false;
			}
		} catch (SQLException ex) {
			error = true;
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 关闭数据库操作对象，ResultSet和Statement被关闭，Connection对象被放回连接池
	 */
	@Override
	public final void close() {
		try {
			final Connection connection = statement.getConnection();
			if (connection.isClosed())
				return;

			if (!connection.getAutoCommit()) {
				// 1 成功执行自动提交
				if (!error) {
					connection.commit();
				}
				connection.setAutoCommit(true);
			}

			// 关闭statement将自动关闭 ResultSet 如果有
			statement.close();

			if (!share) {
				// 事务情况下，会有多个Statement实例，通过此标志避免connection被多次缓存
				if (!Database.CONNECTIONS.offer(connection)) {
					connection.close();
				}
			}
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值 / null
	 */
	public final void setValue(String name, byte[] value) {
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					if (value == null) {
						statement.setNull(index + 1, Types.VARBINARY);
					} else {
						statement.setBytes(index + 1, value);
					}
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值
	 */
	public final void setValue(String name, byte value) {
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					statement.setByte(index + 1, value);
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值 / null
	 */
	public final void setValue(String name, Byte value) {
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					if (value == null) {
						statement.setNull(index + 1, Types.BOOLEAN);
					} else {
						statement.setByte(index + 1, value);
					}
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值
	 */
	public final void setValue(String name, boolean value) {
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					statement.setBoolean(index + 1, value);
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值 / null
	 */
	public final void setValue(String name, Boolean value) {
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					if (value == null) {
						statement.setNull(index + 1, Types.BOOLEAN);
					} else {
						statement.setBoolean(index + 1, value);
					}
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值
	 */
	public final void setValue(String name, short value) {
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					statement.setShort(index + 1, value);
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值 / null
	 */
	public final void setValue(String name, Short value) {
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					if (value == null) {
						statement.setNull(index + 1, Types.SMALLINT);
					} else {
						statement.setShort(index + 1, value);
					}
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值
	 */
	public final void setValue(String name, int value) {
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					statement.setInt(index + 1, value);
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值 / null
	 */
	public final void setValue(String name, Integer value) {
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					if (value == null) {
						statement.setNull(index + 1, Types.INTEGER);
					} else {
						statement.setInt(index + 1, value);
					}
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值
	 */
	public final void setValue(String name, long value) {
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					statement.setLong(index + 1, value);
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值 / null
	 */
	public final void setValue(String name, Long value) {
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					if (value == null) {
						statement.setNull(index + 1, Types.BIGINT);
					} else {
						statement.setLong(index + 1, value);
					}
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值
	 */
	public final void setValue(String name, float value) {
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					statement.setFloat(index + 1, value);
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值 / null
	 */
	public final void setValue(String name, Float value) {
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					if (value == null) {
						statement.setNull(index + 1, Types.FLOAT);
					} else {
						statement.setFloat(index + 1, value);
					}
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值
	 */
	public final void setValue(String name, double value) {
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					statement.setDouble(index + 1, value);
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值 / null
	 */
	public final void setValue(String name, Double value) {
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					if (value == null) {
						statement.setNull(index + 1, Types.DOUBLE);
					} else {
						statement.setDouble(index + 1, value);
					}
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值 / null
	 */
	public final void setValue(String name, String value) {
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					if (value == null) {
						statement.setNull(index + 1, Types.DECIMAL);
					} else {
						statement.setString(index + 1, value);
					}
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值 / null
	 */
	public final void setValue(String name, java.util.Date value) {
		final java.sql.Date v = value == null ? null : new java.sql.Date(value.getTime());
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					if (value == null) {
						statement.setNull(index + 1, Types.DATE);
					} else {
						statement.setDate(index + 1, v);
					}
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值 / null
	 */
	public final void setValue(String name, LocalTime value) {
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					if (value == null) {
						statement.setNull(index + 1, Types.TIME);
					} else {
						statement.setTime(index + 1, Time.valueOf(value));
					}
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值 / null
	 */
	public final void setValue(String name, LocalDate value) {
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					if (value == null) {
						statement.setNull(index + 1, Types.DATE);
					} else {
						statement.setDate(index + 1, Date.valueOf(value));
					}
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值 / null
	 */
	public final void setValue(String name, LocalDateTime value) {
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					if (value == null) {
						statement.setNull(index + 1, Types.TIMESTAMP);
					} else {
						statement.setTimestamp(index + 1, Timestamp.valueOf(value));
					}
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 设置SQL参数值
	 *
	 * @param name 参数名称
	 * @param value 参数值 / null
	 */
	public final void setValue(String name, BigDecimal value) {
		try {
			for (int index = 0; index < namedsql.names.length; index++) {
				if (namedsql.names[index].equals(name)) {
					if (value == null) {
						statement.setNull(index + 1, Types.DECIMAL);
					} else {
						statement.setBigDecimal(index + 1, value);
					}
				}
			}
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * 读取当前记录值
	 *
	 * @param name 字段名
	 * @param default_value 值为null时的替代值
	 * @return 指定字段值 / default_value
	 */
	public final byte[] getValue(String name, byte[] default_value) {
		try {
			if (result == null) {
				if (namedsql.isCall()) {
					CallableStatement callable = (CallableStatement) statement;
					for (int index = 0; index < namedsql.names.length; index++) {
						if (namedsql.names[index].equals(name) && namedsql.types[index] != null) {
							byte[] value = callable.getBytes(index + 1);
							if (callable.wasNull()) {
								return default_value;
							}
							return value;
						}
					}
				}
				throw new SQLException("没有结果集，也没有可返回的参数");
			}
			byte[] value = result.getBytes(name);
			if (result.wasNull()) {
				return default_value;
			}
			return value;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 读取当前记录值
	 *
	 * @param name 字段名
	 * @param default_value 值为null时的替代值
	 * @return 指定字段值 / default_value
	 */
	public final boolean getValue(String name, boolean default_value) {
		try {
			if (result == null) {
				if (namedsql.isCall()) {
					CallableStatement callable = (CallableStatement) statement;
					for (int index = 0; index < namedsql.names.length; index++) {
						if (namedsql.names[index].equals(name) && namedsql.types[index] != null) {
							boolean value = callable.getBoolean(index + 1);
							if (callable.wasNull()) {
								return default_value;
							}
							return value;
						}
					}
				}
				throw new SQLException("没有结果集，也没有可返回的参数");
			}
			boolean value = result.getBoolean(name);
			if (result.wasNull()) {
				return default_value;
			}
			return value;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 读取当前记录值
	 *
	 * @param name 字段名
	 * @param default_value 值为null时的替代值
	 * @return 指定字段值 / default_value
	 */
	public final Boolean getValue(String name, Boolean default_value) {
		try {
			if (result == null) {
				if (namedsql.isCall()) {
					CallableStatement callable = (CallableStatement) statement;
					for (int index = 0; index < namedsql.names.length; index++) {
						if (namedsql.names[index].equals(name) && namedsql.types[index] != null) {
							boolean value = callable.getBoolean(index + 1);
							if (callable.wasNull()) {
								return default_value;
							}
							return value;
						}
					}
				}
				throw new SQLException("没有结果集，也没有可返回的参数");
			}
			boolean value = result.getBoolean(name);
			if (result.wasNull()) {
				return default_value;
			}
			return value;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 读取当前记录值
	 *
	 * @param name 字段名
	 * @param default_value 值为null时的替代值
	 * @return 指定字段值 / default_value
	 */
	public final short getValue(String name, short default_value) {
		try {
			if (result == null) {
				if (namedsql.isCall()) {
					CallableStatement callable = (CallableStatement) statement;
					for (int index = 0; index < namedsql.names.length; index++) {
						if (namedsql.names[index].equals(name) && namedsql.types[index] != null) {
							short value = callable.getShort(index + 1);
							if (callable.wasNull()) {
								return default_value;
							}
							return value;
						}
					}
				}
				throw new SQLException("没有结果集，也没有可返回的参数");
			}
			short value = result.getShort(name);
			if (result.wasNull()) {
				return default_value;
			}
			return value;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 读取当前记录值
	 *
	 * @param name 字段名
	 * @param default_value 值为null时的替代值
	 * @return 指定字段值 / default_value
	 */
	public final Short getValue(String name, Short default_value) {
		try {
			if (result == null) {
				if (namedsql.isCall()) {
					CallableStatement callable = (CallableStatement) statement;
					for (int index = 0; index < namedsql.names.length; index++) {
						if (namedsql.names[index].equals(name) && namedsql.types[index] != null) {
							short value = callable.getShort(index + 1);
							if (callable.wasNull()) {
								return default_value;
							}
							return value;
						}
					}
				}
				throw new SQLException("没有结果集，也没有可返回的参数");
			}
			short value = result.getShort(name);
			if (result.wasNull()) {
				return default_value;
			}
			return value;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 读取当前记录值
	 *
	 * @param name 字段名
	 * @param default_value 值为null时的替代值
	 * @return 指定字段值 / default_value
	 */
	public final int getValue(String name, int default_value) {
		try {
			if (result == null) {
				if (namedsql.isCall()) {
					CallableStatement callable = (CallableStatement) statement;
					for (int index = 0; index < namedsql.names.length; index++) {
						if (namedsql.names[index].equals(name) && namedsql.types[index] != null) {
							int value = callable.getInt(index + 1);
							if (callable.wasNull()) {
								return default_value;
							}
							return value;
						}
					}
				}
				throw new SQLException("没有结果集，也没有可返回的参数");
			}
			int value = result.getInt(name);
			if (result.wasNull()) {
				return default_value;
			}
			return value;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 读取当前记录值
	 *
	 * @param name 字段名
	 * @param default_value 值为null时的替代值
	 * @return 指定字段值 / default_value
	 */
	public final Integer getValue(String name, Integer default_value) {
		try {
			if (result == null) {
				if (namedsql.isCall()) {
					CallableStatement callable = (CallableStatement) statement;
					for (int index = 0; index < namedsql.names.length; index++) {
						if (namedsql.names[index].equals(name) && namedsql.types[index] != null) {
							int value = callable.getInt(index + 1);
							if (callable.wasNull()) {
								return default_value;
							}
							return value;
						}
					}
				}
				throw new SQLException("没有结果集，也没有可返回的参数");
			}
			int value = result.getInt(name);
			if (result.wasNull()) {
				return default_value;
			}
			return value;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 读取当前记录值
	 *
	 * @param name 字段名
	 * @param default_value 值为null时的替代值
	 * @return 指定字段值 / default_value
	 */
	public final long getValue(String name, long default_value) {
		try {
			if (result == null) {
				if (namedsql.isCall()) {
					CallableStatement callable = (CallableStatement) statement;
					for (int index = 0; index < namedsql.names.length; index++) {
						if (namedsql.names[index].equals(name) && namedsql.types[index] != null) {
							long value = callable.getLong(index + 1);
							if (callable.wasNull()) {
								return default_value;
							}
							return value;
						}
					}
				}
				throw new SQLException("没有结果集，也没有可返回的参数");
			}
			long value = result.getLong(name);
			if (result.wasNull()) {
				return default_value;
			}
			return value;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 读取当前记录值
	 *
	 * @param name 字段名
	 * @param default_value 值为null时的替代值
	 * @return 指定字段值 / default_value
	 */
	public final Long getValue(String name, Long default_value) {
		try {
			if (result == null) {
				if (namedsql.isCall()) {
					CallableStatement callable = (CallableStatement) statement;
					for (int index = 0; index < namedsql.names.length; index++) {
						if (namedsql.names[index].equals(name) && namedsql.types[index] != null) {
							long value = callable.getLong(index + 1);
							if (callable.wasNull()) {
								return default_value;
							}
							return value;
						}
					}
				}
				throw new SQLException("没有结果集，也没有可返回的参数");
			}
			long value = result.getLong(name);
			if (result.wasNull()) {
				return default_value;
			}
			return value;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 读取当前记录值
	 *
	 * @param name 字段名
	 * @param default_value 值为null时的替代值
	 * @return 指定字段值 / default_value
	 */
	public final float getValue(String name, float default_value) {
		try {
			if (result == null) {
				if (namedsql.isCall()) {
					CallableStatement callable = (CallableStatement) statement;
					for (int index = 0; index < namedsql.names.length; index++) {
						if (namedsql.names[index].equals(name) && namedsql.types[index] != null) {
							float value = callable.getFloat(index + 1);
							if (callable.wasNull()) {
								return default_value;
							}
							return value;
						}
					}
				}
				throw new SQLException("没有结果集，也没有可返回的参数");
			}
			float value = result.getFloat(name);
			if (result.wasNull()) {
				return default_value;
			}
			return value;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 读取当前记录值
	 *
	 * @param name 字段名
	 * @param default_value 值为null时的替代值
	 * @return 指定字段值 / default_value
	 */
	public final Float getValue(String name, Float default_value) {
		try {
			if (result == null) {
				if (namedsql.isCall()) {
					CallableStatement callable = (CallableStatement) statement;
					for (int index = 0; index < namedsql.names.length; index++) {
						if (namedsql.names[index].equals(name) && namedsql.types[index] != null) {
							float value = callable.getFloat(index + 1);
							if (callable.wasNull()) {
								return default_value;
							}
							return value;
						}
					}
				}
				throw new SQLException("没有结果集，也没有可返回的参数");
			}
			float value = result.getFloat(name);
			if (result.wasNull()) {
				return default_value;
			}
			return value;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 读取当前记录值
	 *
	 * @param name 字段名
	 * @param default_value 值为null时的替代值
	 * @return 指定字段值 / default_value
	 */
	public final double getValue(String name, double default_value) {
		try {
			if (result == null) {
				if (namedsql.isCall()) {
					CallableStatement callable = (CallableStatement) statement;
					for (int index = 0; index < namedsql.names.length; index++) {
						if (namedsql.names[index].equals(name) && namedsql.types[index] != null) {
							double value = callable.getDouble(index + 1);
							if (callable.wasNull()) {
								return default_value;
							}
							return value;
						}
					}
				}
				throw new SQLException("没有结果集，也没有可返回的参数");
			}
			double value = result.getDouble(name);
			if (result.wasNull()) {
				return default_value;
			}
			return value;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 读取当前记录值
	 *
	 * @param name 字段名
	 * @param default_value 值为null时的替代值
	 * @return 指定字段值 / default_value
	 */
	public final Double getValue(String name, Double default_value) {
		try {
			if (result == null) {
				if (namedsql.isCall()) {
					CallableStatement callable = (CallableStatement) statement;
					for (int index = 0; index < namedsql.names.length; index++) {
						if (namedsql.names[index].equals(name) && namedsql.types[index] != null) {
							double value = callable.getDouble(index + 1);
							if (callable.wasNull()) {
								return default_value;
							}
							return value;
						}
					}
				}
				throw new SQLException("没有结果集，也没有可返回的参数");
			}
			double value = result.getDouble(name);
			if (result.wasNull()) {
				return default_value;
			}
			return value;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 读取当前记录值
	 *
	 * @param name 字段名
	 * @param default_value 值为null时的替代值
	 * @return 指定字段值 / default_value
	 */
	public final String getValue(String name, String default_value) {
		try {
			if (result == null) {
				if (namedsql.isCall()) {
					CallableStatement callable = (CallableStatement) statement;
					for (int index = 0; index < namedsql.names.length; index++) {
						if (namedsql.names[index].equals(name) && namedsql.types[index] != null) {
							String value = callable.getString(index + 1);
							if (callable.wasNull()) {
								return default_value;
							}
							return value;
						}
					}
				}
				throw new SQLException("没有结果集，也没有可返回的参数");
			}
			String value = result.getString(name);
			if (result.wasNull()) {
				return default_value;
			}
			return value;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 读取当前记录值
	 *
	 * @param name 字段名
	 * @param default_value 值为null时的替代值
	 * @return 指定字段值 / default_value
	 */
	public final java.util.Date getValue(String name, java.util.Date default_value) {
		try {
			if (result == null) {
				if (namedsql.isCall()) {
					CallableStatement callable = (CallableStatement) statement;
					for (int index = 0; index < namedsql.names.length; index++) {
						if (namedsql.names[index].equals(name) && namedsql.types[index] != null) {
							java.util.Date value = callable.getDate(index + 1);
							if (callable.wasNull()) {
								return default_value;
							}
							return value;
						}
					}
				}
				throw new SQLException("没有结果集，也没有可返回的参数");
			}
			java.util.Date value = result.getDate(name);
			if (result.wasNull()) {
				return default_value;
			}
			return value;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 读取当前记录值
	 *
	 * @param name 字段名
	 * @param default_value 值为null时的替代值
	 * @return 指定字段值 / default_value
	 */
	public final LocalTime getValue(String name, LocalTime default_value) {
		try {
			if (result == null) {
				if (namedsql.isCall()) {
					CallableStatement callable = (CallableStatement) statement;
					for (int index = 0; index < namedsql.names.length; index++) {
						if (namedsql.names[index].equals(name) && namedsql.types[index] != null) {
							Time value = callable.getTime(index + 1);
							if (callable.wasNull()) {
								return default_value;
							}
							return value.toLocalTime();
						}
					}
				}
				throw new SQLException("没有结果集，也没有可返回的参数");
			}
			Time value = result.getTime(name);
			if (result.wasNull()) {
				return default_value;
			}
			return value.toLocalTime();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 读取当前记录值
	 *
	 * @param name 字段名
	 * @param default_value 值为null时的替代值
	 * @return 指定字段值 / default_value
	 */
	public final LocalDate getValue(String name, LocalDate default_value) {
		try {
			if (result == null) {
				if (namedsql.isCall()) {
					CallableStatement callable = (CallableStatement) statement;
					for (int index = 0; index < namedsql.names.length; index++) {
						if (namedsql.names[index].equals(name) && namedsql.types[index] != null) {
							Date value = callable.getDate(index + 1);
							if (callable.wasNull()) {
								return default_value;
							}
							return value.toLocalDate();
						}
					}
				}
				throw new SQLException("没有结果集，也没有可返回的参数");
			}
			Date value = result.getDate(name);
			if (result.wasNull()) {
				return default_value;
			}
			return value.toLocalDate();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 读取当前记录值
	 *
	 * @param name 字段名
	 * @param default_value 值为null时的替代值
	 * @return 指定字段值 / default_value
	 */
	public final LocalDateTime getValue(String name, LocalDateTime default_value) {
		try {
			if (result == null) {
				if (namedsql.isCall()) {
					CallableStatement callable = (CallableStatement) statement;
					for (int index = 0; index < namedsql.names.length; index++) {
						if (namedsql.names[index].equals(name) && namedsql.types[index] != null) {
							Timestamp value = callable.getTimestamp(index + 1);
							if (callable.wasNull()) {
								return default_value;
							}
							return value.toLocalDateTime();
						}
					}
				}
				throw new SQLException("没有结果集，也没有可返回的参数");
			}
			Timestamp value = result.getTimestamp(name);
			if (result.wasNull()) {
				return default_value;
			}
			return value.toLocalDateTime();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 读取当前记录值
	 *
	 * @param name 字段名
	 * @param default_value 值为null时的替代值
	 * @return 指定字段值 / default_value
	 */
	public final BigDecimal getValue(String name, BigDecimal default_value) {
		try {
			if (result == null) {
				if (namedsql.isCall()) {
					CallableStatement callable = (CallableStatement) statement;
					for (int index = 0; index < namedsql.names.length; index++) {
						if (namedsql.names[index].equals(name) && namedsql.types[index] != null) {
							BigDecimal value = callable.getBigDecimal(index + 1);
							if (callable.wasNull()) {
								return default_value;
							}
							return value;
						}
					}
				}
				throw new SQLException("没有结果集，也没有可返回的参数");
			}
			BigDecimal value = result.getBigDecimal(name);
			if (result.wasNull()) {
				return default_value;
			}
			return value;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 获取命名SQL
	 */
	public NamedSQL getNamedSQL() {
		return namedsql;
	}
}
