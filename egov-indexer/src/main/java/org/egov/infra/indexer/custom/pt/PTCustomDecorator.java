package org.egov.infra.indexer.custom.pt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PTCustomDecorator {
	
	@Value("${egov.pt.host}")
	private String ptHost;
	
	@Value("${egov.pt.search.endpoint}")
	private String ptSearchEndPoint;
	
	
	@Autowired
	private RestTemplate restTemplate;
	
	/**
	 * Transforms data by adding consumer codes at the root level, this is needed for denormalization later on.
	 * 
	 * @param properties
	 * @return
	 */
//	public List<Property> transformData(List<Property> properties){
//		for(Property property: properties) {
//			List<String> consumerCodes = new ArrayList<String>();
//			for(PropertyDetail detail: property.getPropertyDetails()) {
//				StringBuilder consumerCode = new StringBuilder();
//				consumerCode.append(property.getPropertyId()).append(":").append(detail.getAssessmentNumber());
//				consumerCodes.add(consumerCode.toString());
//			}
//			property.setConsumerCodes(consumerCodes);
//		}
//		return properties;
//	}
	
	/**
	 * Incase of update, this method fetched all previous assessments of that particular record and hands it over to indexer.
	 * 
	 * @param request
	 * @return
	 */
	public PropertyArrayRequest dataTransformForPTUpdate(PropertyRequest request) {
		ArrayList<Property>propertyArrayList =new ArrayList<>();
		Property property= request.getProperty();
			StringBuilder uri = new StringBuilder();
			uri.append(ptHost).append(ptSearchEndPoint).append("?tenantId=").append(property.getTenantId()).append("&propertyIds=").append(property.getPropertyId()).append("&audit=true");
			Map<String, Object> apiRequest = new HashMap<>();
			apiRequest.put("RequestInfo", request.getRequestInfo());
			try {
				PropertyResponse response = restTemplate.postForObject(uri.toString(), apiRequest, PropertyResponse.class);
				if(null != response) {
					if(!CollectionUtils.isEmpty(response.getProperties())) {
						for(Property propertyFromSearch:response.getProperties())
						{
							propertyArrayList.add(propertyFromSearch);
						}

					}else {
						log.info("Zero properties returned from the service!");
						log.info("Request: "+apiRequest);
						log.info("URI: "+uri);
						return null;
					}
				}else {
					log.info("NULL returned from service!");
					log.info("Request: "+apiRequest);
					log.info("URI: "+uri);
					return null;
				}
			}catch(Exception e) {
				log.error("Exception while fetching properties: ",e);
				log.info("Request: "+apiRequest);
				log.info("URI: "+uri);
				return null;
			}
			PropertyArrayRequest propertyArrayRequest=PropertyArrayRequest.builder().requestInfo(request.getRequestInfo())
					.properties(propertyArrayList).build();
		log.info("Record updated with previous assessments");
		return propertyArrayRequest;
		
	}

}
