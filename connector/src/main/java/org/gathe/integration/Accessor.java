package org.gathe.integration;

import org.w3c.dom.Document;

import java.util.HashMap;
import java.util.List;

/**
 * Created by zolotov on 01.11.13.
 */
public interface Accessor {

    public void setConnector(Connector connector);

    //get endpoint schema
    public List<DataClass> getSchema();

    //get object description from endpoint
    public Document get(String transactionId, String className, String uuid);

    //update object description from endpoint
    public boolean update(String transactionId, String className, String uuid, String data);

    //remove object from endpoint
    public boolean remove(String transactionId, String className, String uuid);

    //get UUID by identifier (seek)
    public String getUuidByIdentifier(String transactionId, String className, String identifierName, String identifierValue);

    //resolve identifier by UUID (reverse-seek)
    public String getIdentifierByUuid(String transactionId, String className, String identifierName, String uuidValue);

    //check presence by identifier
    public boolean checkByIdentifier(String transactionId, String className, String identifierName, String identifierValue);

    public String specify(String transactionId, String genericClassName, String uuid);
}
