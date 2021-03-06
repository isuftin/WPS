/**
 * ﻿Copyright (C) 2012 - 2014 52°North Initiative for Geospatial Open Source
 * Software GmbH
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 as published
 * by the Free Software Foundation.
 *
 * If the program is linked with libraries which are licensed under one of
 * the following licenses, the combination of the program with the linked
 * library is not considered a "derivative work" of the program:
 *
 *       • Apache License, version 2.0
 *       • Apache Software License, version 1.0
 *       • GNU Lesser General Public License, version 3
 *       • Mozilla Public License, versions 1.0, 1.1 and 2.0
 *       • Common Development and Distribution License (CDDL), version 1.0
 *
 * Therefore the distribution of the program linked with libraries licensed
 * under the aforementioned licenses, is permitted by the copyright holders
 * if the distribution is compliant with both the GNU General Public
 * License version 2 and the aforementioned licenses.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 */
package org.n52.wps.server.feed;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;



import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.n52.wps.server.feed.movingcode.MovingCodeObject;

/**
 * @author Matthias Mueller, TU Dresden
 *
 */
public class AlgorithmFeed {
	private final String feedURL;
	private final File localPath;
	private static final String ZIP_MIME_TYPE = "application/zip";

	private MovingCodeObject[] feedAlgorithms;

	Logger LOGGER = LoggerFactory.getLogger(AlgorithmFeed.class);

	public AlgorithmFeed (String feedURL, String localPath){
		this.feedURL = feedURL;
		this.localPath = new File(localPath);
		initializeFeed();
	}

	public ArrayList<MovingCodeObject> getMovingCodeObjects (URI supportedContainerURNs[], URI[] providedComponentURN){
		ArrayList<MovingCodeObject> mcoList = new ArrayList<MovingCodeObject>();

		for (MovingCodeObject currentMCO : feedAlgorithms){
			if (currentMCO.isContainer(supportedContainerURNs) && currentMCO.isSufficientRuntimeEnvironment(providedComponentURN)){
				mcoList.add(currentMCO);
			}
		}

		return mcoList;
	}

	private void deleteLocalCopy() throws IOException{
		FileUtils.deleteDirectory(localPath);
	}

	private void initializeFeed(){

		try {
			long feedLastModified = testFeed();
			boolean localExists = localPath.exists();

			if (localExists){
				if (localPath.lastModified() != feedLastModified){
					deleteLocalCopy();
					createLocalCopy();
					localPath.setLastModified(feedLastModified);
				}
			} else {
				createLocalCopy();
				localPath.setLastModified(feedLastModified);
			}

		} catch (IOException e) {
			LOGGER.error("Aborting Feed initialization due to an Exception.");
		}

		String[] describeProcessFiles = retrieveProcessDescriptions(localPath);
		ArrayList<MovingCodeObject> mcoList = new ArrayList<MovingCodeObject>();

		// create new MovingCodeObjects
		for (String currentFileName : describeProcessFiles){
			File currentFile = new File (localPath.getAbsolutePath() + File.separator + currentFileName);
			mcoList.add(new MovingCodeObject(currentFile, localPath));
		}
		feedAlgorithms = mcoList.toArray(new MovingCodeObject[mcoList.size()]);
	}

	private void createLocalCopy() throws IOException{

		GetMethod get = new GetMethod(feedURL);
		HttpClient client = new HttpClient();
		//create all non existing folders
		new File(localPath.getParent()).mkdirs();

		try {
			client.executeMethod(get);
			InputStream is = get.getResponseBodyAsStream();
			unzipFeed(is);
		} catch (HttpException e) {
			LOGGER.error("Unable to connect to Feed: " + feedURL);
			throw new IOException();
		} catch (IOException e) {
			LOGGER.error("Unable to decode Feed: " + feedURL);
			throw new IOException();
		}

	}

	private long testFeed() throws IOException{
		long lastFeedUpdate = 0;

		try {
			URL url = new URL(feedURL);
			URLConnection conn = url.openConnection();

			// TODO implement checks on MimeType
			String contentType = conn.getContentType();
			if (!contentType.equalsIgnoreCase(ZIP_MIME_TYPE)){
				LOGGER.warn("Uncommon MimeType found at Feed URL: " + contentType);
			}
			lastFeedUpdate = conn.getLastModified();
		} catch (MalformedURLException e) {
			LOGGER.error("Invalid feedURL: " + feedURL);
			throw new IOException();
		} catch (IOException e) {
			LOGGER.error("Error connecting to feedURL: " + feedURL);
			throw new IOException();
		}
		return lastFeedUpdate;
	}

	private void unzipFeed(InputStream is) throws IOException {
		ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is));
	    ZipEntry entry;

	    while ((entry = zis.getNextEntry()) != null) {
	    	String fileName = entry.getName();
	    	File newFile = new File(localPath + File.separator + fileName);
	    	if(!entry.isDirectory()){
	    		LOGGER.info("Unzipping: " + newFile.getAbsolutePath());

		    	//create all non existing folders
		    	new File(newFile.getParent()).mkdirs();
		    	FileOutputStream fos = new FileOutputStream(newFile);

		    	int size;
		    	byte[] buffer = new byte[2048];
		    	BufferedOutputStream bos = new BufferedOutputStream(fos, buffer.length);
		    	while ((size = zis.read(buffer, 0, buffer.length)) != -1) {
		    		bos.write(buffer, 0, size);
		    	}

		    	bos.flush();
		    	bos.close();
	    	}
	    }
	    zis.close();
	    is.close();

	    LOGGER.info("All contents unzipped. Folder was: " + localPath);
	}

	private static String[] retrieveProcessDescriptions(File feedDirectory){
		String[] describeProcessFiles = feedDirectory.list(new FilenameFilter() {
		    public boolean accept(File d, String name) {
		       return name.endsWith(".xml");
		    }
		});
		return describeProcessFiles;
	}

}
