package com.wjj.jdbc.elasticsearch;

import com.wjj.jdbc.jest.JestResultSet;
import com.wjj.jdbc.jest.JestType;
import com.wjj.jdbc.jest.JestUtil;
import com.wjj.jdbc.util.LOG;
import io.searchbox.client.JestResult;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.client.Client;
import org.elasticsearch.plugin.nlpcn.QueryActionElasticExecutor;
import org.elasticsearch.plugin.nlpcn.executors.CsvExtractorException;
import org.nlpcn.es4sql.SearchDao;
import org.nlpcn.es4sql.exception.SqlParseException;
import org.nlpcn.es4sql.jdbc.ObjectResult;
import org.nlpcn.es4sql.jdbc.ObjectResultsExtractor;
import org.nlpcn.es4sql.query.QueryAction;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @Author wangjiajun
 * @Date 2017/8/14 10:43
 */
public class ElasticSearchPreparedStatement implements PreparedStatement{
    Logger logger = LOG.getLogger(ElasticSearchPreparedStatement.class);
    private Connection conn;
    private String sql;
    LinkedList<String> sqlList = new LinkedList<String>();//存放分解的sql片段
    private ResultSet rs = null;
    public ElasticSearchPreparedStatement(Connection conn, String sql){
        this.conn = conn;
        this.sql = sql;
        sqlList.clear();
        buildSqlList(sql);
    }
    //分解sql，如select * from tableName where a=? and b=?分解成 ["select * from tableName where a=","?"," and b=","?"]
    private void buildSqlList(String sql){
        int index = sql.indexOf("?");
        if(index<0){
            sqlList.add(sql);
            return;
        }
        String preStr = sql.substring(0,index);
        sqlList.add(preStr);
        sqlList.add("?");
        String afterStr = sql.substring(index+1);
        buildSqlList(afterStr);
    }
    private String getSql(){
        logger.debug("the sql is:"+sql);
        String sql = StringUtils.join(sqlList.toArray(),"");
        return sql;
    }

    @Override
    public void close() throws SQLException {
        sqlList.clear();//做下清空操作
        this.rs = null;
    }
    @Override
    public boolean execute(String sql) throws SQLException {
        try {
            if(isExecuteSql(sql)){
                executeSql(sql);
            }else{
                executeJest(sql);
            }
        }catch (Exception e){
            logger.error(e.getMessage(),e);
        }
        return true;
    }

    @Override
    public boolean execute() throws SQLException {
        String sql = getSql();
        return execute(sql);
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        String sql = getSql();
        try{
            if(isExecuteSql(sql)){  //执行sql
                ObjectResult extractor = getObjectResult(true, sql, false, false, true);
                List<String> headers = extractor.getHeaders();
                List<List<Object>> lines = extractor.getLines();
                return new ElasticSearchSqlResultSet(this, headers, lines);
            }else{  //执行rest api
                JestResult result = getJestResult(sql);
                return new JestResultSet(result);
            }
        }catch (Exception e){
            logger.error(e.getMessage(),e);
        }
        return null;
    }

    @Override
    public int executeUpdate() throws SQLException {
        String sql = getSql();
        if(isExecuteSql(sql)){
            throw new SQLException("executeUpdate not support in ElasticSearch sql");
        }else{
            try{
                JestResult result = getJestResult(sql);
                return result.getResponseCode();
            }catch (Exception e){
                logger.error("执行jest操作发生错误",e);
            }
        }
        return -1;
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        return this.rs;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        if(rs != null&&(rs instanceof JestResultSet)){
            JestResult jr = (JestResult)rs.getObject(0);
            return jr.getResponseCode();
        }
        return -1;
    }
    /**
     * 执行sql查询
     * @throws SQLException
     */
    private void executeSql(String sql)throws SQLException,Exception{
        ObjectResult extractor = getObjectResult(true,sql, false, false, true);
        List<String> headers = extractor.getHeaders();
        List<List<Object>> lines = extractor.getLines();
        this.rs = new ElasticSearchSqlResultSet(this, headers, lines);
    }
    /**
     * 使用jest客户端执行rest查询
     */
    private void executeJest(String restBody)throws SQLException,Exception{
        JestResult result = getJestResult(restBody);
        this.rs = new JestResultSet(result);
    }

    /**
     * 判断是否是执行sql操作
     * @param sql
     * @return true:sql操作。false：rest操作
     */
    private boolean isExecuteSql(String sql){
        int i = StringUtils.indexOf(sql,"?");
        if(i==-1){
            i=sql.length();
        }
        String head = StringUtils.substring(sql,0,i).trim();
        if(StringUtils.startsWithIgnoreCase(head,"select")){
            return true;
        }
        return false;
    }
    private ObjectResult getObjectResult(boolean flat, String query, boolean includeScore, boolean includeType, boolean includeId) throws SqlParseException, SQLFeatureNotSupportedException, Exception, CsvExtractorException {
        Client client = ((ElasticSearchConnection)conn).getClient();
        SearchDao searchDao = new org.nlpcn.es4sql.SearchDao(client);
        QueryAction queryAction = searchDao.explain(query);
        Object execution = QueryActionElasticExecutor.executeAnyAction(searchDao.getClient(), queryAction);
        return new ObjectResultsExtractor(includeScore, includeType, includeId).extractResults(execution, flat);
    }

    /**
     * 调用jest客户端执行查询
     * @param restBody
     * @return
     * @throws Exception
     */
    public JestResult getJestResult(String restBody) throws Exception{
        JestType op_type = JestUtil.checkOperateType(restBody);
        String restMapping = JestUtil.getRestMapping(restBody);
        String[] indexes = JestUtil.getIndexes(restBody);
        String[] types = JestUtil.getTypes(restBody);
        String id = JestUtil.getId(restBody);
        Map<String,Object> parameter = JestUtil.getParameters(restBody);
        JestResult result = null;
        switch (op_type){
            case ADD:
                result = StringUtils.isBlank(id)?
                        JestUtil.insert(restMapping,indexes[0],types[0],parameter):
                        JestUtil.insert(restMapping,indexes[0],types[0],id,parameter);
                break;
            case SELECT:
                result = JestUtil.query(restMapping,indexes,types,parameter);
                break;
            case GET:
                result = JestUtil.get(indexes[0],types[0],id);
                break;
            case UPDATE:
                result = JestUtil.update(restMapping,indexes[0],types[0],id.split(","));
                break;
            case DELETE:
                result = JestUtil.delete(indexes[0],types[0],id.split(","));
                break;
        }
        return result;
    }
    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {

    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        sqlList.set(2*parameterIndex-1,String.valueOf(x));
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {

    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        sqlList.set(2*parameterIndex-1,String.valueOf(x));
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        sqlList.set(2*parameterIndex-1,String.valueOf(x));
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        sqlList.set(2*parameterIndex-1,String.valueOf(x));
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        sqlList.set(2*parameterIndex-1,String.valueOf(x));
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        sqlList.set(2*parameterIndex-1,String.valueOf(x));
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        sqlList.set(2*parameterIndex-1,String.valueOf(x));
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        sqlList.set(2*parameterIndex-1,"'"+x+"'");
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {

    }

    @Override
    public void setDate(int parameterIndex, Date x) throws SQLException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        setString(parameterIndex,sdf.format(x));
    }

    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        sqlList.set(2*parameterIndex-1,String.valueOf(x.getTime()));
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        sqlList.set(2*parameterIndex-1,String.valueOf(x.getTime()));
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {

    }

    @Override
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {

    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {

    }

    @Override
    public void clearParameters() throws SQLException {

    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {

    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {

    }

    @Override
    public void addBatch() throws SQLException {

    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {

    }

    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {

    }

    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {

    }

    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {

    }

    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {

    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return null;
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {

    }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {

    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {

    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {

    }

    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {

    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        return null;
    }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {

    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {

    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {

    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {

    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {

    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {

    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {

    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {

    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {

    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {

    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {

    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {

    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {

    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {

    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {

    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {

    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {

    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {

    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {

    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        return null;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        return 0;
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        return 0;
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {

    }

    @Override
    public int getMaxRows() throws SQLException {
        return 0;
    }

    @Override
    public void setMaxRows(int max) throws SQLException {

    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {

    }

    @Override
    public int getQueryTimeout() throws SQLException {
        return 0;
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {

    }

    @Override
    public void cancel() throws SQLException {

    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {

    }

    @Override
    public void setCursorName(String name) throws SQLException {

    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return false;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {

    }

    @Override
    public int getFetchDirection() throws SQLException {
        return 0;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {

    }

    @Override
    public int getFetchSize() throws SQLException {
        return 0;
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        return 0;
    }

    @Override
    public int getResultSetType() throws SQLException {
        return 0;
    }

    @Override
    public void addBatch(String sql) throws SQLException {

    }

    @Override
    public void clearBatch() throws SQLException {

    }

    @Override
    public int[] executeBatch() throws SQLException {
        return new int[0];
    }

    @Override
    public Connection getConnection() throws SQLException {
        return null;
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        return false;
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        return null;
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return 0;
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return 0;
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        return 0;
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        return false;
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        return false;
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        return false;
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return 0;
    }

    @Override
    public boolean isClosed() throws SQLException {
        return false;
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {

    }

    @Override
    public boolean isPoolable() throws SQLException {
        return false;
    }

    @Override
    public void closeOnCompletion() throws SQLException {

    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return null;
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return false;
    }
}
