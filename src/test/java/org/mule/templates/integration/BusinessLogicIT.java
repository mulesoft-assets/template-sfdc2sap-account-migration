/**
 * Mule Anypoint Template
 * Copyright (c) MuleSoft, Inc.
 * All rights reserved.  http://www.mulesoft.com
 */
package org.mule.templates.integration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import junit.framework.Assert;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.mule.MessageExchangePattern;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.processor.chain.SubflowInterceptingChainLifecycleWrapper;
import org.mule.tck.junit4.rule.DynamicPort;

import com.mulesoft.module.batch.BatchTestHelper;
import com.sforce.soap.partner.SaveResult;

/**
 * The objective of this class is to validate the correct behavior of the Mule Template that make calls to external systems.
 * 
 * The test will invoke the batch process and afterwards check that the accounts had been correctly created and that the ones that should be filtered are not in
 * the destination sand box.
 * 
 * The test validates that no account will get sync as result of the integration.
 * 
 * @author damiansima
 * @author MartinZdila
 * @author Vlado Andoga
 */
public class BusinessLogicIT extends AbstractTemplateTestCase {
	
	protected static final int TIMEOUT_SECONDS = 120;

	private static final String KEY_ID = "Id";
	private static final String KEY_NAME = "Name";
	private static final String KEY_NUMBER_OF_EMPLOYEES = "NumberOfEmployees";
	private static final String KEY_INDUSTRY = "Industry";
	
	private static final String TEMPLATE_NAME = "sf2sa";
	
	private SubflowInterceptingChainLifecycleWrapper retrieveAccountFromSapFlow;
	private SubflowInterceptingChainLifecycleWrapper deleteAccountFromSalesforceFlow;
	private SubflowInterceptingChainLifecycleWrapper deleteAccountFromSapFlow;
	
	private List<Map<String, Object>> createdAccountsInSalesforce = new ArrayList<Map<String, Object>>();
	
	private BatchTestHelper helper;
	
	@Rule
	public DynamicPort port = new DynamicPort("http.port");
	
	@BeforeClass
	public static void init() {
	}
	
	@Before
	public void setUp() throws Exception {	
		
		helper = new BatchTestHelper(muleContext);
	
		retrieveAccountFromSapFlow = getSubFlow("retrieveAccountFromSapFlow");
		retrieveAccountFromSapFlow.initialise();
		
		deleteAccountFromSalesforceFlow = getSubFlow("deleteAccountsFromSalesforceFlow");
		deleteAccountFromSalesforceFlow.initialise();
		
		deleteAccountFromSapFlow = getSubFlow("deleteAccountsFromSapFlow");
		deleteAccountFromSapFlow.initialise();
		
		createTestDataInSandBox();
	}
	
	@After
	public void tearDown() throws Exception {		
		deleteTestAccountsFromSalesforce(createdAccountsInSalesforce);
		deleteTestAccountsFromSap(createdAccountsInSalesforce);
	}

	@Test
	public void testMainFlow() throws Exception {
		
		runFlow("triggerFlow");

		helper.awaitJobTermination(TIMEOUT_SECONDS * 1000, 500);
		helper.assertJobWasSuccessful();
	
		Map<String, Object> payload0 = invokeRetrieveFlow(retrieveAccountFromSapFlow, createdAccountsInSalesforce.get(0));
		Assert.assertNotNull("The account 0 should have been sync but is null", payload0);
		Assert.assertEquals("The account 0 should have been sync (Name)", createdAccountsInSalesforce.get(0).get(KEY_NAME), payload0.get(KEY_NAME));

		Map<String, Object>  payload1 = invokeRetrieveFlow(retrieveAccountFromSapFlow, createdAccountsInSalesforce.get(1));
		Assert.assertNotNull("The account 1 should have been sync but is null", payload1);
		Assert.assertEquals("The account 1 should have been sync (Name)", createdAccountsInSalesforce.get(1).get(KEY_NAME), payload1.get(KEY_NAME));
		
		Map<String, Object>  payload2 = invokeRetrieveFlow(retrieveAccountFromSapFlow, createdAccountsInSalesforce.get(2));
		Assert.assertNull("The account 2 should have not been sync", payload2);
	}


	private void createTestDataInSandBox() throws MuleException, Exception {
		// Create object in target system to be updated
		String uniqueSuffix = "_" + System.currentTimeMillis();
		
		Map<String, Object> sapAccount3 = new HashMap<String, Object>();
		sapAccount3.put(KEY_NAME, "Name_3_SAP" + TEMPLATE_NAME + uniqueSuffix);
		List<Map<String, Object>> createdAccountInSap = new ArrayList<Map<String, Object>>();
		createdAccountInSap.add(sapAccount3);
	
		runFlow("createAccountsInSapFlow", createdAccountInSap);
	
		Thread.sleep(1001); // this is here to prevent equal LastModifiedDate
		
		// Create accounts in source system to be or not to be synced
	
		// This account should be synced
		Map<String, Object> sfdcAccount0 = new HashMap<String, Object>();
		sfdcAccount0.put(KEY_NAME, "Name_0_SFDC" + uniqueSuffix);
		sfdcAccount0.put(KEY_NUMBER_OF_EMPLOYEES, 6000);
		sfdcAccount0.put(KEY_INDUSTRY, "Education");
		createdAccountsInSalesforce.add(sfdcAccount0);
				
		// This account should be synced (update)
		Map<String, Object> sfdcAccount1 = new HashMap<String, Object>();
		sfdcAccount1.put(KEY_NAME,  sapAccount3.get(KEY_NAME));
		sfdcAccount1.put(KEY_NUMBER_OF_EMPLOYEES, 7100);
		sfdcAccount1.put(KEY_INDUSTRY, "Government");
		createdAccountsInSalesforce.add(sfdcAccount1);

		// This account should not be synced because of employees / industry
		Map<String, Object> sfdcAccount2 = new HashMap<String, Object>();
		sfdcAccount2.put(KEY_NAME, "Name_2_SFDC" + uniqueSuffix);
		sfdcAccount2.put(KEY_NUMBER_OF_EMPLOYEES, 204);
		sfdcAccount2.put(KEY_INDUSTRY, "Energetic");
		createdAccountsInSalesforce.add(sfdcAccount2);

		MuleEvent event = runFlow("createAccountsInSalesforceFlow", createdAccountsInSalesforce);
		List<?> results = (List<?>) event.getMessage().getPayload();
		
		// assign Salesforce-generated IDs
		for (int i = 0; i < createdAccountsInSalesforce.size(); i++) {
			createdAccountsInSalesforce.get(i).put(KEY_ID, ((SaveResult) results.get(i)).getId());
		}

		System.out.println("Results after adding: " + createdAccountsInSalesforce.toString());
	}

	@SuppressWarnings("unchecked")
	protected Map<String, Object> invokeRetrieveFlow(SubflowInterceptingChainLifecycleWrapper flow, Map<String, Object> payload) throws Exception {
		MuleEvent event = flow.process(getTestEvent(payload, MessageExchangePattern.REQUEST_RESPONSE));
		Object resultPayload = event.getMessage().getPayload();
		List<Map<String, Object>> resultPayload2 = (List<Map<String, Object>>) resultPayload;
		return resultPayload2.isEmpty() ? null : resultPayload2.get(0).get("CustomerNumber") == null ? null : resultPayload2.get(0);
	}
	
	private void deleteTestAccountsFromSalesforce(List<Map<String, Object>> createdAccountsInSalesforce) throws Exception {
		deleteTestEntityFromSandBox(deleteAccountFromSalesforceFlow, createdAccountsInSalesforce, KEY_ID);
	}

	private void deleteTestAccountsFromSap(List<Map<String, Object>> createdAccountsInSalesforce) throws Exception {
		List<Map<String, Object>> createdAccountsInSap = new ArrayList<Map<String, Object>>();
		for (Map<String, Object> c : createdAccountsInSalesforce) {
			Map<String, Object> account = invokeRetrieveFlow(retrieveAccountFromSapFlow, c);
			if (account != null) {
				createdAccountsInSap.add(account);
			}
		}
		deleteTestEntityFromSandBox(deleteAccountFromSapFlow, createdAccountsInSap, "CustomerNumber");
	}
	
	private MuleEvent deleteTestEntityFromSandBox(SubflowInterceptingChainLifecycleWrapper deleteFlow, List<Map<String, Object>> entitities, String idName) throws Exception {
		List<String> idList = new ArrayList<String>();
		for (Map<String, Object> c : entitities) {
			idList.add(c.get(idName).toString());
		}
		return deleteFlow.process(getTestEvent(idList, MessageExchangePattern.REQUEST_RESPONSE));
	}

}
