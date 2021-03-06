/**
 * Copyright (c) 2000-2012 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.hadoop.util;

import com.liferay.hadoop.job.Map;
import com.liferay.hadoop.job.Reduce;
import com.liferay.portal.kernel.util.StreamUtil;
import com.liferay.portal.kernel.util.StringBundler;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portlet.documentlibrary.store.Store;

import java.io.IOException;
import java.io.InputStream;

import javax.servlet.ServletContext;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobStatus;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.mapred.TextOutputFormat;

/**
 * @author Raymond Augé
 */
public class HadoopManager {

	public static JobConf createNewJobConf() {
		return new JobConf(_sharedJobConf);
	}

	public static FileSystem getFileSystem()
		throws IOException {

		return getIstance()._getFileSystem();
	}

	public static String getFullDirName(
		long companyId, long repositoryId, String dirName) {

		StringBundler sb = new StringBundler(5);

		sb.append(StringPool.SLASH);
		sb.append(companyId);
		sb.append(StringPool.SLASH);
		sb.append(repositoryId);

		if (Validator.isNotNull(dirName)) {
			sb.append(StringPool.SLASH);
			sb.append(dirName);
		}

		return sb.toString();
	}

	public static Path getFullDirPath(
		long companyId, long repositoryId, String dirName) {

		return new Path(getFullDirName(companyId, repositoryId, dirName));
	}

	public static String getFullVersionFileName(
		long companyId, long repositoryId, String fileName, String version) {

		StringBundler sb = new StringBundler(3);

		sb.append(getFullDirName(companyId, repositoryId, fileName));
		sb.append(StringPool.SLASH);

		if (Validator.isNull(version)) {
			sb.append(Store.VERSION_DEFAULT);
		}
		else {
			sb.append(version);
		}

		return sb.toString();
	}

	public static Path getFullVersionFilePath(
		long companyId, long repositoryId, String fileName, String version) {

		return new Path(
			getFullVersionFileName(companyId, repositoryId, fileName, version));
	}

	public static HadoopManager getIstance() {
		return _instance;
	}

	public static JobClient getJobClient() throws IOException {
		return getIstance()._getJobClient();
	}

	public static ServletContext getServletContext() {
		return _servletContext;
	}

	public static void setServletContext(ServletContext servletContext) {
		_servletContext = servletContext;
	}

	public static void runJob(StoreEvent storeEvent) throws IOException {
		FileSystem fileSystem = getFileSystem();

		if (_servletContext == null) {
			return;
		}

		JobClient jobClient = getJobClient();

		Path inputPath = new Path(
			"/index".concat(storeEvent.getRootPath().toString()).concat("/*"));
		Path outputPath = new Path(
			"/wordcount".concat(
				storeEvent.getRootPath().toString()).concat("/results"));

		try {
			if (_runningJob == null) {
				if (!fileSystem.exists(_jobPath)) {
					FSDataOutputStream outputStream = null;

					try {
						outputStream = fileSystem.create(_jobPath);

						InputStream inputStream =
							_servletContext.getResourceAsStream(
								"/WEB-INF/lib/hadoop-job.jar");

						StreamUtil.transfer(inputStream, outputStream, false);
					}
					finally {
						StreamUtil.cleanUp(outputStream);
					}
				}

				if (fileSystem.exists(outputPath)) {
					fileSystem.rename(
						outputPath, outputPath.getParent().suffix(
							"/.results-" + System.currentTimeMillis()));
				}

				_jobConf = new JobConf(_sharedJobConf);

				_jobConf.setJobName("Word Count");
				_jobConf.setJarByClass(Map.class);
				_jobConf.setOutputKeyClass(Text.class);
				_jobConf.setOutputValueClass(IntWritable.class);
				_jobConf.setMapperClass(Map.class);
				_jobConf.setCombinerClass(Reduce.class);
				_jobConf.setReducerClass(Reduce.class);
				_jobConf.setInputFormat(TextInputFormat.class);
				_jobConf.setOutputFormat(TextOutputFormat.class);

				DistributedCache.addArchiveToClassPath(
					_jobPath, _jobConf, fileSystem);

				FileInputFormat.setInputPaths(_jobConf, inputPath);
				FileOutputFormat.setOutputPath(_jobConf, outputPath);

				_runningJob = jobClient.submitJob(_jobConf);
			}

			int jobState = _runningJob.getJobState();

			if ((jobState != JobStatus.RUNNING) &&
				(jobState != JobStatus.PREP)) {

				System.out.println("Re-issuing the word count job.");

				if (fileSystem.exists(outputPath)) {
					fileSystem.rename(
						outputPath, outputPath.getParent().suffix(
							"/.results-" + System.currentTimeMillis()));
				}

				_runningJob = jobClient.submitJob(_jobConf);
			}
		}
		catch (Exception ioe) {
			ioe.printStackTrace();
		}
	}

	private FileSystem _getFileSystem() throws IOException {
		if (_fileSystem != null) {
			return _fileSystem;
		}

		_fileSystem = FileSystem.get(_configuration);

		return _fileSystem;
	}

	private JobClient _getJobClient() throws IOException {
		if (_jobClient != null) {
			return _jobClient;
		}

		_jobClient = new JobClient(_sharedJobConf);

		return _jobClient;
	}

	private HadoopManager() {

		// TODO be more declarative!

		System.setProperty("HADOOP_USER_NAME", "hduser");

		_configuration = new Configuration();

		// HDSF

		_configuration.set("fs.default.name", "hdfs://localhost:54310");

		// Map/Reduce

		_configuration.set("mapred.job.tracker", "localhost:54311");

		_sharedJobConf = new JobConf(_configuration);

		_jobPath = new Path("/wordcount/jars/hadoop-job.jar");
	}

	private static HadoopManager _instance = new HadoopManager();

	private static ServletContext _servletContext;

	private Configuration _configuration;
	private FileSystem _fileSystem;

	private JobClient _jobClient;
	private static JobConf _jobConf;
	private static Path _jobPath;
	private static RunningJob _runningJob;
	private static JobConf _sharedJobConf;

}