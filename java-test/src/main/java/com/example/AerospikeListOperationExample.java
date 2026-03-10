/** * DISCLAIMER: This code is for illustrative purposes only. * It is not intended for production use and is provided "as is" * without warranty of any kind, express or implied. */
package com.example;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Value;
import com.aerospike.client.Operation;
import com.aerospike.client.policy.WritePolicy;

import com.aerospike.client.cdt.ListOperation;
import com.aerospike.client.cdt.ListPolicy;
import com.aerospike.client.cdt.ListOrder;
import com.aerospike.client.cdt.ListWriteFlags;

import com.aerospike.client.Record;
import com.aerospike.client.policy.Policy;

import com.aerospike.client.exp.Exp;
import com.aerospike.client.exp.ExpOperation;
import com.aerospike.client.exp.ExpWriteFlags;
import com.aerospike.client.exp.Expression;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class AerospikeListOperationExample {

        private final AerospikeClient client;

        /**
         * Constructor to initialize Aerospike client
         * 
         * @param client
         */
        public AerospikeListOperationExample(AerospikeClient client) {
                this.client = client;
        }

        /**
         * Main class - calls write and read example
         */
        public static void main(String[] args) {
                String configPath = "application.properties";
                if (args.length > 0) {
                        configPath = args[0];
                }
                Properties props = loadProperties(configPath);

                String host = props.getProperty("aerospike.host");
                int port = Integer.parseInt(props.getProperty("aerospike.port"));
                String namespace = props.getProperty("aerospike.namespace");
                String set = props.getProperty("aerospike.set");
                String userKey = props.getProperty("aerospike.userKey");

                AerospikeClient client = new AerospikeClient(host, port);
                Key key = new Key(namespace, set, userKey);
                try {
                        AerospikeListOperationExample example = new AerospikeListOperationExample(client);
                        example.writeAndReadExample(namespace, set, key);

                } finally {
                        client.close();
                }
        }

        /**
         * Loads application.properties file
         * 
         * @return Properties object
         */
        private static Properties loadProperties(String path) {
                Properties props = new Properties();

                try (InputStream input = new FileInputStream(path)) {
                        props.load(input);
                } catch (IOException e) {
                        throw new RuntimeException("Failed to load properties from " + path, e);
                }

                return props;
        }

        /**
         * Example of writing to a list bin with additional operations and then reading
         * back the record to verify
         * 
         * @param namespace
         * @param set
         * @param userKey
         */
        private void writeAndReadExample(String namespace, String set, Key userKey) {

                // Generate dynamic values
                String listBinName = "emailHistory";
                String listValue = "user" + System.currentTimeMillis() + "@example.com";

                String primaryKeyBin = "accountId";
                String primaryKeyValue = "acct-" + UUID.randomUUID();

                String createdAtBin = "created_ts";

                Operation[] operations = getOperationsToBeAddedToList(
                                listBinName,
                                listValue,
                                primaryKeyBin,
                                primaryKeyValue,
                                createdAtBin,
                                true, // update updatedAt
                                true // no-fail enabled
                );

                WritePolicy writePolicy = new WritePolicy();
                client.operate(writePolicy, userKey, operations);

                // Read back
                Policy readPolicy = new Policy();
                Record record = client.get(readPolicy, userKey);

                if (record != null) {
                        System.out.println("Record found:");
                        System.out.println(listBinName + ": " + record.getList(listBinName));
                        System.out.println(primaryKeyBin + ": " + record.getString(primaryKeyBin));
                        System.out.println(createdAtBin + ": " + record.getLong(createdAtBin));
                        System.out.println("updatedAt: " + record.getLong("updatedAt"));
                } else {
                        System.out.println("Record not found.");
                }
        }

        /**
         * Generates list of operations to be added to list bin with additional
         * operations like writing primary key bin,
         * writing immutable int bin and updating updatedAt timestamp based on provided
         * parameters
         * 
         * @param binNameToBeAddedToList
         * @param addBinValToList
         * @param primaryKeyBinName
         * @param primaryKeyBinVal
         * @param notUpdatableIntBinName
         * @param isUpdatedAtRequired
         * @param isNoFailEnabled
         * @return
         */
        public Operation[] getOperationsToBeAddedToList(String binNameToBeAddedToList,
                        String addBinValToList,
                        String primaryKeyBinName,
                        String primaryKeyBinVal,
                        String notUpdatableIntBinName,
                        boolean isUpdatedAtRequired,
                        boolean isNoFailEnabled) {

                if (Objects.isNull(binNameToBeAddedToList) || Objects.isNull(addBinValToList)) {
                        throw new IllegalArgumentException(
                                        String.format(
                                                        "Operation cannot be created as either ListBin or ListBinValue is null for primaryKeyName-%s and primaryKeyValue-%s",
                                                        primaryKeyBinName, primaryKeyBinVal));
                }

                Expression expForIntBin = getExpressionForOperationForIntBin(
                                notUpdatableIntBinName);

                Expression expForStringBin = getExpressionForOperationForStringBin(
                                primaryKeyBinName,
                                primaryKeyBinVal);

                int listFlags = ListWriteFlags.ADD_UNIQUE;

                if (isNoFailEnabled) {
                        listFlags |= ListWriteFlags.NO_FAIL;
                }

                ListPolicy listPolicy = new ListPolicy(ListOrder.ORDERED, listFlags);
                List<Operation> operations = new ArrayList<>();

                // Append unique value
                operations.add(
                        ListOperation.append(
                                listPolicy,
                                binNameToBeAddedToList,
                                Value.get(addBinValToList)
                        )
                );

                // Write primary key bin if provided
                if (Objects.nonNull(primaryKeyBinVal) && Objects.nonNull(primaryKeyBinName)) {
                        operations.add(
                                ExpOperation.write(
                                        primaryKeyBinName,
                                        expForStringBin,
                                        ExpWriteFlags.DEFAULT
                                )
                        );
                }

                // Write immutable int bin only if it does not exist
                if (Objects.nonNull(expForIntBin)) {
                        operations.add(
                                ExpOperation.write(
                                        notUpdatableIntBinName,
                                        expForIntBin,
                                        ExpWriteFlags.DEFAULT
                                )
                        );
                }

                // Update updatedAt timestamp
                if (isUpdatedAtRequired) {
                        operations.add(getUpdatedAtPutOperation());
                }

                return operations.toArray(new Operation[0]);
        }

        /**
         * Expression to write current timestamp to int bin if it does not exist,
         * otherwise keep existing value
         * 
         * @param binName
         * @return
         */
        private Expression getExpressionForOperationForIntBin(String binName) {
                return Exp.build(
                        Exp.cond(
                                Exp.binExists(binName),
                                Exp.intBin(binName), // keep existing value
                                Exp.val(System.currentTimeMillis()) // otherwise set timestamp
                                
                        )
                );
        }

        /**
         * Expression to write provided value to string bin only if it does not exist,
         * otherwise keep existing value
         * 
         * @param binName
         * @param value
         * @return
         */
        private Expression getExpressionForOperationForStringBin(String binName, String value) {
                return Exp.build(Exp.val(value));
        }

        /**
         * Operation to update int bin with current timestamp
         * 
         * @return
         */
        private Operation getUpdatedAtPutOperation() {
                return Operation.put(
                        new com.aerospike.client.Bin(
                        "updatedAt",
                        System.currentTimeMillis()
                        )
                );
        }
}