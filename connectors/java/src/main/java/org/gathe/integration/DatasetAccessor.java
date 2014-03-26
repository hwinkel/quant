package org.gathe.integration;

import org.apache.log4j.Logger;

import javax.jms.JMSException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public abstract class DatasetAccessor extends BaseAccessor {
    protected AccessorSchema schema;
    protected Logger LOG = Logger.getLogger(this.getClass());
    protected String systemId;
    protected Connection bindingDB;
    protected String bindingPrefix;
    protected HashMap<String, String> identifiers = new HashMap<>();

    public DatasetAccessor(String bindingPrefix, String systemId) {
        this.bindingPrefix = bindingPrefix;
        this.systemId = systemId;
    }

    /**
     * Calculate hash for dataset row
     *
     * @param row Dataset Row
     * @return Hash String (sha1)
     */
    protected String getHash(HashMap<String, String> row) {
        String hashRow = "";
        String hashResult = "";
        for (AccessorField field : schema.getSchemaFields()) {
            if (field.isIdentifier()) continue; //skip identifiers
            if (!hashRow.isEmpty()) hashRow += "~";
            hashRow += row.get(field.getPath());
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA1");
            hashResult = md.digest(hashRow.getBytes("utf-8")).toString();
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException nsae) {
            LOG.error("Hash generation error: " + nsae.getLocalizedMessage());
        }
        return hashResult;
    }


    public abstract List<DataClass> getSchema();

    public List<DataClass> getClassSchema(String dataClass) {

        DataClass dc = new DataClass(dataClass);
        dc.setMatchable(true);
        for (AccessorField field : schema.getSchemaFields()) {
            LOG.info("Extracting " + field.getId() + ":" + field.getPath() + ":" + field.getDescription());
            if (field.isIdentifier()) {
                String identifier = field.getId();
                if (!field.getScope().equalsIgnoreCase("global")) identifier = this.systemId + ":" + identifier;
                dc.addCheck(identifier);
                dc.addIdentifier(identifier);
                identifiers.put(identifier, field.getKey());
            } else {
                DataElement de = new DataElement(field.getPath(), field.getDescription());
                dc.addElement(de);
            }
        }

        List<DataClass> schemaEntries = new ArrayList<>();
        schemaEntries.add(dc);
        return schemaEntries;
    }


    protected abstract HashMap<String, String> getRow(String identifierName, String identifierValue, boolean applyTransform);

    protected abstract ArrayList<HashMap<String, String>> getDataset(String transactionId, String className);

    /**
     * Get Hash by UUID value
     *
     * @param transactionId transaction identifier
     * @param className     className
     * @param uuid          uuid
     * @return Hash String (sha1)
     */
    private String getHashByUuid(String transactionId, String className, String uuid) {
        String[] idents = identifiers.keySet().toArray(new String[0]);
        String identifierName = idents[0];
        String id = this.getIdentifierByUuid(transactionId, className, identifierName, uuid);
        HashMap<String, String> row = this.getRow(identifierName, id, false);
        if (row.isEmpty()) return null;
        else return this.getHash(row);
//        for (int i = 0; i < data.size(); i++) {
//            HashMap<String, String> row = data.get(i);
//            if (row.containsKey("#" + identifierName) && row.get("#" + identifierName).equalsIgnoreCase(id)) {
//                return this.getHash(row);
//            }
//        }
//        return null;
    }

    /**
     * Check for row modification
     *
     * @param transactionId transaction identifier
     * @param className     classname
     * @param uuid          uuid
     * @return true if modified
     */

    public boolean isModified(String transactionId, String className, String uuid) {
        String actualHash = getHashByUuid(transactionId, className, uuid);
        try {
            PreparedStatement ps = bindingDB.prepareStatement("SELECT hash FROM " + this.bindingPrefix + "_" + className + " WHERE uuid=? AND disabled=0");
            ps.setString(1, uuid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return (!rs.getString("hash").equalsIgnoreCase(actualHash));
            } else {
                return true;    //no record
            }
        } catch (SQLException e) {
            LOG.error("SQL Exception: " + e.getLocalizedMessage());
        }
        return false;
    }

    /**
     * Apply transformation rules
     *
     * @param row datarow
     * @return transformed datarow
     */
    public HashMap<String, String> transform(HashMap<String, String> row) {

        for (String key : row.keySet()) {
            LOG.debug("Row data: " + key + " " + row.get(key));
            //if (key.startsWith("#")) continue;      //don't skip identifier
            List<AccessorField> fields = schema.getSchemaFields();
            for (AccessorField field : fields) {

                LOG.debug("Checking field " + field);

                boolean matchedField = false;
                if (field.isIdentifier()) {
                    matchedField = (field.getId().equalsIgnoreCase(key.substring(1)));
                } else {
                    matchedField = (field.getPath().equalsIgnoreCase(key));
                }

                if (matchedField) {
                    List<ReplaceJAXB> replaces = field.getReplaces();
                    if (replaces == null) continue;
                    for (ReplaceJAXB replaceRule : replaces) {
//                        LOG.debug("Comparing "+replaceRule.getFrom()+" with "+row.get(key));
                        if (replaceRule.getFrom().equalsIgnoreCase(row.get(key))) {
//                            LOG.debug("Matched - replace with "+replaceRule.getTo());
                            row.put(key, replaceRule.getTo());
                            break;
                        }
                    }
                }
            }
        }

        return row;
    }

    /**
     * Scanning for matched entries
     *
     * @param transactionId transaction identifier
     * @param className     classname
     * @param filters       filters set
     * @return comma separated uuid list
     */
    @Override
    public String[] match(String transactionId, String className, HashMap<String, String> filters) {

        //search for all entries
        //data[identifier] -> uuid
        String[] idents = identifiers.keySet().toArray(new String[0]);
        String identifierName = idents[0];
        LOG.info("Identifier: " + identifierName);
        ArrayList<String> uuids = new ArrayList<>();
        ArrayList<HashMap<String, String>> data = this.getDataset(transactionId, className);
        for (int i = 0; i < data.size(); i++) {
            HashMap<String, String> row = transform(data.get(i));
            String uuid = getUuidByIdentifier(transactionId, className, identifierName, row.get("#" + identifierName));
            uuids.add(uuid);
        }
        return uuids.toArray(new String[0]);
    }

    /**
     * Get path (or identifier) by key
     *
     * @param key
     * @return Path (identifier)
     */
    protected abstract String getPath(String key);

    /**
     * Compare records
     *
     * @param row    data row
     * @param helper update helper object
     * @return true if identical
     */

    public boolean equals(HashMap<String, String> row, UpdateHelper helper) {
        for (String rowKey : row.keySet()) {
            if (!rowKey.startsWith("#")) continue;          //skip identifiers
            //todo: check scope
            if (helper.get(rowKey) != null) {
                if (!helper.get(rowKey).trim().equalsIgnoreCase(row.get(rowKey).trim())) return false;
            }
        }
        return true;
    }

    /**
     * Update data in real dataset
     *
     * @param className       class name
     * @param identifierName  identifier name
     * @param identifierValue identifier value
     * @param row             Helper class for row data
     * @return true if success
     */

    public abstract boolean updateData(String className, String identifierName, String identifierValue, UpdateHelper row);

    /**
     * Insert new data row to real dataset
     *
     * @param className       class name
     * @param identifierName  identifier name
     * @param identifierValue identifier value
     * @param row             Helper class for row data
     * @return Hashmap for new keys data
     */

    public abstract HashMap<String, String> insertData(String className, String identifierName, String identifierValue, UpdateHelper row);

    /**
     * Update record!
     *
     * @param className
     * @param helper
     * @return
     */
    @Override
    public boolean update(String className, UpdateHelper helper) {
        String uuid = helper.getUuid();

        try {
            PreparedStatement ps = bindingDB.prepareStatement("SELECT * FROM " + this.bindingPrefix + "_" + className + " WHERE uuid=? AND disabled=0");
            ps.setString(1, uuid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String id = rs.getString("id");
                String identifier = rs.getString("name");
                if (this.checkByIdentifier(helper.getTransactionId(), className, identifier, id)) {
                    return updateData(className, identifier, id, helper);           //update existing!
                } else {
                    return (insertData(className, identifier, id, helper) != null);      //rebind existing!
                }
            }

            ArrayList<HashMap<String, String>> data = this.getDataset(helper.getTransactionId(), className);

            for (int i = 0; i < data.size(); i++) {
                HashMap<String, String> row = data.get(i);
                row = this.transform(row);
                //searching for full match
                if (this.equals(row, helper)) {
                    String[] idents = identifiers.keySet().toArray(new String[0]);
                    String hash = this.getHash(row);
                    //bind with all identifiers
                    for (String identifierName : idents) {
                        String id = row.get("#" + identifierName);
                        PreparedStatement uuidUpdate = this.bindingDB.prepareStatement("INSERT INTO " + this.bindingPrefix + "_" + className + " (uuid,id,name,disabled,actual,value,hash) VALUES (?,?,?,0,NOW(),'',?)");
                        uuidUpdate.setString(1, uuid);
                        uuidUpdate.setString(2, identifierName);
                        uuidUpdate.setString(3, id);
                        uuidUpdate.setString(4, hash);
                        uuidUpdate.executeUpdate();
                    }
                    String id0 = idents[0];
                    String val0 = row.get("#" + id0);
                    //if (this.checkByIdentifier(helper.getTransactionId(), className, identifier, id)) {
                    return updateData(className, id0, val0, helper);           //update existing!
                    //} else {
                    //   return (insertData(className, identifier, id, helper)!=null);      //rebind existing!
                    //}
                }
            }

            //add new record!
            String newUuid = UUID.randomUUID().toString();
            String classIdentifiers = "";
            for (String ks : identifiers.keySet()) {
                if (!classIdentifiers.isEmpty()) classIdentifiers += ",";
                classIdentifiers += ks;
            }

            HashMap<String, String> keys = insertData(className, classIdentifiers, null, helper);
            if (keys == null) return false;
            //register bindings
            for (String key : keys.keySet()) {
                String value = keys.get(key);
                PreparedStatement uuidUpdate = this.bindingDB.prepareStatement("INSERT INTO " + this.bindingPrefix + "_" + className + " (uuid,name,id,disabled,actual,value,hash) VALUES (?,?,?,0,NOW(),'',?)");
                uuidUpdate.setString(1, uuid);
                uuidUpdate.setString(2, key);
                uuidUpdate.setString(3, value);
                uuidUpdate.setString(4, "");        //todo: hash
                uuidUpdate.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            LOG.error("SQL Exception: " + e.getLocalizedMessage());
            return false;
        }
    }

    /**
     * Translate local identifier to global uuid
     *
     * @param transactionId   transaction identifier
     * @param className       classname
     * @param identifierName  identifier name
     * @param identifierValue identifier value
     * @return Global UUID
     */
    @Override
    public String getUuidByIdentifier(String transactionId, String className, String identifierName, String identifierValue) {
        try {
            PreparedStatement st = this.bindingDB.prepareStatement("SELECT uuid FROM " + this.bindingPrefix + "_" + className + " WHERE id=? AND name=? AND disabled=0");
            LOG.info("Searching for " + identifierName + " = " + identifierValue);
            st.setString(1, identifierValue);
            st.setString(2, identifierName);
            ResultSet rs = st.executeQuery();
            if (rs.next()) {
                //record exists
                return rs.getString("uuid");
            } else {

                //поиск совпадений с другими идентификаторами строки
                ArrayList<HashMap<String, String>> data = this.getDataset(transactionId, className);
                for (int i = 0; i < data.size(); i++) {
                    HashMap<String, String> row = transform(data.get(i));
                    if (!row.containsKey("#" + identifierName) || !row.get("#" + identifierName).equalsIgnoreCase(identifierValue))
                        continue;
                    LOG.debug("Found matched record");
                    for (String key : row.keySet()) {
                        if (!key.startsWith("#") || key.equalsIgnoreCase("#" + identifierName)) continue;
                        String name = key.substring(1);
                        String value = row.get(key);
                        LOG.debug("Comparing with " + name + ":" + value);
                        try {
                            st = this.bindingDB.prepareStatement("SELECT uuid FROM " + this.bindingPrefix + "_" + className + " WHERE id=? AND name=? AND disabled=0");
                            st.setString(1, value);
                            st.setString(2, name);
                            rs = st.executeQuery();
                            if (rs.next()) {
                                LOG.debug("Found!!!");
                                String uuid = rs.getString("uuid");
                                PreparedStatement uuidUpdate = this.bindingDB.prepareStatement("INSERT INTO " + this.bindingPrefix + "_" + className + " (uuid,id,disabled,name,actual,value) VALUES (?,?,0,?,NOW(),'')");
                                uuidUpdate.setString(1, uuid);
                                uuidUpdate.setString(2, identifierValue);
                                uuidUpdate.setString(3, identifierName);
                                uuidUpdate.executeUpdate();
                                return uuid;
                            }
                        } catch (SQLException se) {
                            LOG.error("SQL Exception in unify: " + se.getLocalizedMessage());
                        }
                    }

                    //todo: choice scenario
                    //request ESB with global scope identifiers

                    if (!connector.isSelfRequest(transactionId, className)) {

                        row = this.getRow(identifierName, identifierValue, true);
//                    for (int i = 0; i < data.size(); i++) {
//                        HashMap<String, String> row = data.get(i);
                        //search for identifier
//                        if (!row.containsKey("#" + identifierName) || !row.get("#" + identifierName).equalsIgnoreCase(identifierValue))
//                            continue;
                        for (AccessorField field : schema.getSchemaFields()) {
                            if (field.isIdentifier() && field.getScope().equalsIgnoreCase("global")) {
                                LOG.info("Searching in outer world for " + field.getId());
                                String identifier = field.getId();
                                String uuidGlobal = null;
                                try {
                                    uuidGlobal = connector.unify(transactionId, className, identifier, row.get("#" + identifier), false);
                                } catch (JMSException e) {
                                }
                                LOG.info("Found in global scope: " + uuidGlobal);
                                if (uuidGlobal != null) {
                                    //object found in outer world!
                                    PreparedStatement uuidUpdate = this.bindingDB.prepareStatement("INSERT INTO " + this.bindingPrefix + "_" + className + " (uuid,id,disabled,name,actual,value) VALUES (?,?,0,?,NOW(),'')");
                                    LOG.debug("Adding to " + uuidGlobal + " " + identifierName + ":" + identifierValue);
                                    uuidUpdate.setString(1, uuidGlobal);
                                    uuidUpdate.setString(2, identifierValue);
                                    uuidUpdate.setString(3, identifierName);
                                    uuidUpdate.executeUpdate();
                                    return uuidGlobal;
                                }
                            }
                        }
                    }
//                    }
                    LOG.info("Object not found!!!!");
                    //todo: binding via match

                    String newUuid = UUID.randomUUID().toString();
                    LOG.info("Registering new record");
                    PreparedStatement uuidUpdate = this.bindingDB.prepareStatement("INSERT INTO " + this.bindingPrefix + "_" + className + " (uuid,id,disabled,name,actual,value) VALUES (?,?,0,?,NOW(),'')");
                    uuidUpdate.setString(1, newUuid);
                    uuidUpdate.setString(2, identifierValue);
                    uuidUpdate.setString(3, identifierName);
                    uuidUpdate.executeUpdate();
                    return newUuid;
                }
                return null;
            }
        } catch (SQLException e) {
            LOG.error("SQL Exception (in unify): " + e.getLocalizedMessage());
        }
        return super.getUuidByIdentifier(transactionId, className, identifierName, identifierValue);
    }

    @Override
    public boolean get(String className, GetHelper helper) {
        String uuid = helper.getUuid();
        //todo: check identifiers
        String[] idents = identifiers.keySet().toArray(new String[0]);
        String id = this.getIdentifierByUuid(helper.getTransactionId(), className, idents[0], uuid);
        LOG.info("Resolved identifier: " + id);
        String identifierName = idents[0];
        HashMap<String, String> row = getRow(identifierName, id, true);
        if (row == null) return true;
        else {
//        for (int i = 0; i < data.size(); i++) {
//            HashMap<String, String> row = data.get(i);
//            if (row.containsKey("#" + identifierName) && row.get("#" + identifierName).equalsIgnoreCase(id)) {
            //fill the object
            String hash = this.getHash(row);
            try {
                PreparedStatement ps = bindingDB.prepareStatement("UPDATE " + bindingPrefix + "_" + className + " SET ACTUAL=NOW(),hash=? WHERE uuid=? AND disabled=0");
                ps.setString(1, hash);
                ps.setString(2, uuid);
                ps.executeUpdate();
            } catch (SQLException e) {
                LOG.error("SQL Exception (in get) " + e.getLocalizedMessage());
            }
            for (String rowKey : row.keySet()) {
                if (rowKey.startsWith("#")) continue;
                String referenced = null;
                for (AccessorField field : this.schema.getSchemaFields()) {
                    if (field.getPath().equalsIgnoreCase(rowKey)) {
                        referenced = field.getRef();
                    }
                }
                if (referenced != null) {
                    String referencedClass = referenced.substring(0, referenced.indexOf("."));
                    String referencedId = referenced.substring(referenced.indexOf(".") + 1);
                    try {
                        helper.put(rowKey, connector.unify(helper.getTransactionId(), referencedClass, referencedId, row.get(rowKey), false));
                    } catch (JMSException e) {
                        LOG.error("Error when resolving: " + e.getLocalizedMessage());
                    }
                } else {
                    helper.put(rowKey, row.get(rowKey));
                }
            }
            return true;
//            }
        }
//        return false;
    }

    /**
     * Translate global uuid to local identifier
     *
     * @param transactionId  transaction identifier
     * @param className      classname
     * @param identifierName identifier name
     * @param uuidValue      uuid
     * @return Local identifier
     */
    @Override
    public String getIdentifierByUuid(String transactionId, String className, String identifierName, String uuidValue) {
        try {
            PreparedStatement st = this.bindingDB.prepareStatement("SELECT id FROM " + this.bindingPrefix + "_" + className + " WHERE uuid=? AND name=? AND disabled=0");
            st.setString(1, uuidValue);
            st.setString(2, identifierName);
            ResultSet rs = st.executeQuery();
            if (rs.next()) {
                return rs.getString("id");
            }
        } catch (SQLException e) {
            LOG.error("SQL Exception (in identify): " + e.getLocalizedMessage());
        }
        return null;
    }

    public abstract boolean checkByIdentifier(String transactionId, String className, String identifierName, String identifierValue);

/*
        //todo: optimize
    }
*/
}
