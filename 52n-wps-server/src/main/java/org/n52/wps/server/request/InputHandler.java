/***************************************************************
 This implementation provides a framework to publish processes to the
web through the  OGC Web Processing Service interface. The framework 
is extensible in terms of processes and data handlers. It is compliant 
to the WPS version 0.4.0 (OGC 05-007r4). 

 Copyright (C) 2006 by con terra GmbH

 Authors: 
	Theodor Foerster, ITC, Enschede, the Netherlands
	Carsten Priess, Institute for geoinformatics, University of
Muenster, Germany
	Timon Ter Braak, University of Twente, the Netherlands
	Bastian Schaeffer, Institute for geoinformatics, University of Muenster, Germany


 Contact: Albert Remke, con terra GmbH, Martin-Luther-King-Weg 24,
 48155 Muenster, Germany, 52n@conterra.de

 This program is free software; you can redistribute it and/or
 modify it under the terms of the GNU General Public License
 version 2 as published by the Free Software Foundation.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program (see gnu-gpl v2.txt); if not, write to
 the Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 Boston, MA  02111-1307, USA or visit the web page of the Free
 Software Foundation, http://www.fsf.org.

 Created on: 13.06.2006
 ***************************************************************/
package org.n52.wps.server.request;

import java.io.IOException;
import java.io.InputStream;
// import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import net.opengis.wps.x100.InputDescriptionType;
import net.opengis.wps.x100.InputType;
import net.opengis.wps.x100.ProcessDescriptionType;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.n52.wps.io.IParser;
import org.n52.wps.io.ParserFactory;
import org.n52.wps.io.xml.AbstractXMLParser;
import org.n52.wps.server.ExceptionReport;
import org.n52.wps.server.RepositoryManager;
import org.n52.wps.util.BasicXMLTypeFactory;

/**
 * Handles the input of the client and stores it into a Map.
 */
public class InputHandler {

	protected static Logger LOGGER = Logger.getLogger(InputHandler.class);
	protected Map<String, Object> inputLayers = new HashMap<String, Object>();
	protected Map<String, Object> inputParameters = new HashMap<String, Object>();
	private ProcessDescriptionType processDesc;
	private String algorithmIdentifier = null; // Needed to take care of handling a conflict between different parsers.
	/**
	 * Initializes a parser that handles each (line of) input based on the type of input.
	 * @see #handleComplexData(IOValueType)
	 * @see #handleComplexValueReference(IOValueType)
	 * @see #handleLiteralData(IOValueType)
	 * @see #handleBBoxValue(IOValueType)
	 * @param inputs The client input
	 */
	public InputHandler(InputType[] inputs, String algorithmIdentifier) throws ExceptionReport{
		this. algorithmIdentifier = algorithmIdentifier;
		this.processDesc = RepositoryManager.getInstance().getAlgorithm(algorithmIdentifier).getDescription();
		for(InputType input : inputs) {
			String inputID = input.getIdentifier().getStringValue();
			
			if(input.getData() != null) {
				if(input.getData().getComplexData() != null) {
					handleComplexData(input);
				}
				else if(input.getData().getLiteralData() != null) {
					handleLiteralData(input);
				}
				else if(input.getData().getBoundingBoxData() != null) {
					handleBBoxValue(input);
				}
			}
			else if(input.getReference() != null) {
				handleComplexValueReference(input);
			}
			else {
				throw new ExceptionReport("Error while accessing the inputValue: " + inputID, 
						ExceptionReport.INVALID_PARAMETER_VALUE);
			}
		}
	}
	
	/**
	 * Handles the complexValue, which in this case should always include XML 
	 * which can be parsed into a FeatureCollection.
	 * @param input The client input
	 * @throws ExceptionReport If error occured while parsing XML
	 */
	protected void handleComplexData(InputType input) throws ExceptionReport{
		String inputID = input.getIdentifier().getStringValue();
		String complexValue = input.getData().getComplexData().xmlText();
		InputDescriptionType inputDesc = null;
		for(InputDescriptionType tempDesc : this.processDesc.getDataInputs().getInputArray()) {
			if(inputID.equals(tempDesc.getIdentifier().getStringValue())) {
				inputDesc = tempDesc;
				break;
			}
		}

		if(inputDesc == null) {
			LOGGER.debug("input cannot be found in description for " + processDesc.getIdentifier().getStringValue() + "," + inputID);
		}
		
		String schema = input.getData().getComplexData().getSchema();
		String encoding = input.getData().getComplexData().getEncoding();
		String mimeType = input.getData().getComplexData().getMimeType();
		if(schema == null) {
			schema = inputDesc.getComplexData().getDefault().getFormat().getSchema();
		}
		if(mimeType == null) {
			mimeType = inputDesc.getComplexData().getDefault().getFormat().getMimeType();
		}
		if(encoding == null) {
			encoding = inputDesc.getComplexData().getDefault().getFormat().getEncoding();
		}
		
		IParser parser = null;
//		if(this.algorithmIdentifier==null)
//			parser = ParserFactory.getInstance().getParser(schema, mimeType, encoding);
//		else
			parser = ParserFactory.getInstance().getParser(schema, mimeType, encoding, this.algorithmIdentifier);
		if(parser == null) {
			parser = ParserFactory.getInstance().getSimpleParser();
		}
		Object collection = null;
		if(parser instanceof AbstractXMLParser) {
			try {
				collection = ((AbstractXMLParser)parser).parseXML(complexValue);
			}
			catch(RuntimeException e) {
				throw new ExceptionReport("Error occured, while XML parsing", 
						ExceptionReport.NO_APPLICABLE_CODE, e);
			}
		}
		else {
			throw new ExceptionReport("parser does not support operation: " + parser.getClass().getName(), ExceptionReport.INVALID_PARAMETER_VALUE);
		}
		inputLayers.put(inputID, collection);
	}

	/**
	 * Handles the literalData
	 * @param input The client's input
	 * @throws ExceptionReport If the type of the parameter is invalid.
	 */
	protected void handleLiteralData(InputType input) throws ExceptionReport {
		String inputID = input.getIdentifier().getStringValue();
		String parameter = input.getData().getLiteralData().getStringValue();
		String xmlDataType = input.getData().getLiteralData().getDataType();
		if(xmlDataType == null) {
			InputDescriptionType inputDesc = null;
			for(InputDescriptionType tempDesc : this.processDesc.getDataInputs().getInputArray()) {
				if(inputID.equals(tempDesc.getIdentifier().getStringValue())) {
					inputDesc = tempDesc;
					break;
				}
			}
			xmlDataType = inputDesc.getLiteralData().getDataType().getReference();
		}
		Object parameterObj = null;
		try {
			parameterObj = BasicXMLTypeFactory.getBasicJavaObject(xmlDataType, parameter);
		}
		catch(RuntimeException e) {
			throw new ExceptionReport("The passed parameterValue: " + parameter + ", but should be of type: " + xmlDataType, ExceptionReport.INVALID_PARAMETER_VALUE);
		}
		if(parameterObj == null) {
			throw new ExceptionReport("XML datatype as LiteralParameter is not supported by the server: dataType " + xmlDataType, 
					ExceptionReport.INVALID_PARAMETER_VALUE);
		}
		inputParameters.put(inputID, parameterObj);
		
	}
	
	/**
	 * Handles the ComplexValueReference
	 * @param input The client input
	 * @throws ExceptionReport If the input (as url) is invalid, or there is an error while parsing the XML.
	 */
	protected void handleComplexValueReference(InputType input) throws ExceptionReport{
		String inputID = input.getIdentifier().getStringValue();
		
		// OutputStream postContent = null;
		if(input.getReference().isSetBody()) {
			
		}
		String dataURLString = input.getReference().getHref();
		LOGGER.debug("Loading data from: " + dataURLString);
		InputDescriptionType inputDesc = null;
		for(InputDescriptionType tempDesc : this.processDesc.getDataInputs().getInputArray()) {
			if(inputID.equals(tempDesc.getIdentifier().getStringValue())) {
				inputDesc = tempDesc;
				break;
			}
		}

		if(inputDesc == null) {
			LOGGER.debug("Input cannot be found in description for " + 
					this.processDesc.getIdentifier().getStringValue() + "," + inputID);
		}
		
		String schema = input.getReference().getSchema();
		String encoding = input.getReference().getEncoding();
		String mimeType = input.getReference().getMimeType();
		if(schema == null) {
			schema = inputDesc.getComplexData().getDefault().getFormat().getSchema();
		}
		if(mimeType == null) {
			mimeType = inputDesc.getComplexData().getDefault().getFormat().getMimeType();
		}
		if(encoding == null) {
			encoding = inputDesc.getComplexData().getDefault().getFormat().getEncoding();
		}
		
		LOGGER.debug("Loading parser for: "+ schema + "," + mimeType + "," + encoding);
		IParser parser = null;
//		if(this.algorithmIdentifier==null)
//			parser = ParserFactory.getInstance().getParser(schema, mimeType, encoding);
//		else
			parser = ParserFactory.getInstance().getParser(schema, mimeType, encoding, this.algorithmIdentifier);
		if(parser == null) {
			LOGGER.warn("No applicable schema found. Trying simpleGML");
			parser = ParserFactory.getInstance().getSimpleParser();
		}
		try {
			
			/****PROXY*****/
			URL dataURL = new URL(dataURLString);
			//URL dataURL = new URL("http", "proxy", 8080, dataURLString);
			Object collection = null;
			try {
				// Do not give a direct inputstream.
				// The XML handlers cannot handle slow connections
				URLConnection conn = dataURL.openConnection();
				conn.setRequestProperty("Accept-Encoding", "gzip");
				conn.setRequestProperty("Content-type", mimeType);
				//Handling POST with referenced document
				if(input.getReference().isSetBodyReference()) {
					String bodyReference = input.getReference().getBodyReference().getHref();
					URL bodyReferenceURL = new URL (bodyReference);
					URLConnection bodyReferenceConn = bodyReferenceURL.openConnection();
					bodyReferenceConn.setRequestProperty("Accept-Encoding", "gzip");
					InputStream referenceInputStream = retrievingZippedContent(bodyReferenceConn);
					IOUtils.copy(referenceInputStream, conn.getOutputStream());
				}
				//Handling POST with inline message
				else if (input.getReference().isSetBody()) {
					conn.setDoOutput(true);
					
					input.getReference().getBody().save(conn.getOutputStream());
				}
				InputStream inputStream = retrievingZippedContent(conn);
				collection = parser.parse(inputStream);				
			}
			catch(RuntimeException e) {
				throw new ExceptionReport("Error occured while parsing XML", 
											ExceptionReport.NO_APPLICABLE_CODE, e);
			}
			inputLayers.put(inputID, collection);
		}
		catch(MalformedURLException e) {
			throw new ExceptionReport("The inputURL of the execute is wrong: inputID: " + inputID + " | dataURL: " + dataURLString, 
										ExceptionReport.INVALID_PARAMETER_VALUE );
		}
		catch(IOException e) {
			 throw new ExceptionReport("Error occured while receiving the complexReferenceURL: inputID: " + inputID + " | dataURL: " + dataURLString, 
					 				ExceptionReport.INVALID_PARAMETER_VALUE );
		}
	}
	
	
	/**
	 * Handles BBoxValue
	 * @param input The client input
	 */
	protected void handleBBoxValue(InputType input) throws ExceptionReport{
		//String inputID = input.getIdentifier().getStringValue();
		throw new ExceptionReport("BBox is not supported", ExceptionReport.OPERATION_NOT_SUPPORTED);
	}
	
	/**
	 * Gets the resulting InputLayers from the parser
	 * @return A map with the parsed input
	 */
	public HashMap<String, Object> getParsedInputLayers(){
		return new HashMap<String, Object>(inputLayers);
	}
	
	/**
	 * Gets the resulting InputParameters from the parser
	 * @return A map with the parsed input
	 */
	public HashMap<String, Object> getParsedInputParameters(){
		return new HashMap<String, Object>(inputParameters);
	}
	
	private InputStream retrievingZippedContent(URLConnection conn) throws IOException{
		String contentType = conn.getContentEncoding();
		if(contentType != null && contentType.equals("gzip")) {
			return new GZIPInputStream(conn.getInputStream());
		}
		else{
			return conn.getInputStream();
		}
	}
}