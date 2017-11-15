/*
 * Copyright 2002-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.file.remote.synchronizer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Pattern;

import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.context.Lifecycle;
import org.springframework.integration.endpoint.AbstractFetchLimitingMessageSource;
import org.springframework.integration.file.DefaultDirectoryScanner;
import org.springframework.integration.file.DirectoryScanner;
import org.springframework.integration.file.FileReadingMessageSource;
import org.springframework.integration.file.filters.AcceptOnceFileListFilter;
import org.springframework.integration.file.filters.CompositeFileListFilter;
import org.springframework.integration.file.filters.FileListFilter;
import org.springframework.integration.file.filters.FileSystemPersistentAcceptOnceFileListFilter;
import org.springframework.integration.file.filters.RegexPatternFileListFilter;
import org.springframework.integration.metadata.SimpleMetadataStore;
import org.springframework.messaging.Message;
import org.springframework.util.Assert;

/**
 * Factors out the common logic between the FTP and SFTP adapters. Designed to
 * be extensible to handle adapters whose task it is to synchronize a remote
 * file system with a local file system (NB: this does *NOT* handle pushing
 * files TO the remote file system that exist uniquely in the local file system.
 * It only handles pulling from the remote file system - as you would expect
 * from an 'inbound' adapter).
 * <p>
 * The base class supports configuration of whether the remote file system and
 * local file system's directories should be created on start (what 'creating a
 * directory' means to the specific adapter is of course implementation
 * specific).
 * <p>
 * This class is to be used as a pair with an implementation of
 * {@link AbstractInboundFileSynchronizer}. The synchronizer must
 * handle the work of actually connecting to the remote file system and
 * delivering new {@link File}s.
 *
 * @author Josh Long
 * @author Oleg Zhurakousky
 * @author Gary Russell
 * @author Artem Bilan
 * @author Venil Noronha
 */
public abstract class AbstractInboundFileSynchronizingMessageSource<F>
		extends AbstractFetchLimitingMessageSource<File> implements Lifecycle {

	private volatile boolean running;

	/**
	 * Should the endpoint attempt to create the local directory? True by default.
	 */
	private volatile boolean autoCreateLocalDirectory = true;

	/**
	 * An implementation that will handle the chores of actually connecting to and synchronizing
	 * the remote file system with the local one, in an inbound direction.
	 */
	private final AbstractInboundFileSynchronizer<F> synchronizer;

	/**
	 * Directory to which things should be synchronized locally.
	 */
	private volatile File localDirectory;

	/**
	 * The actual {@link FileReadingMessageSource} that monitors the local file system once files are synchronized.
	 */
	private final FileReadingMessageSource fileSource;

	private volatile FileListFilter<File> localFileListFilter;

	/**
	 * Whether the {@link DirectoryScanner} was explicitly set.
	 */
	private volatile boolean scannerExplicitlySet = false;

	public AbstractInboundFileSynchronizingMessageSource(AbstractInboundFileSynchronizer<F> synchronizer) {
		this(synchronizer, null);
	}

	public AbstractInboundFileSynchronizingMessageSource(AbstractInboundFileSynchronizer<F> synchronizer,
			Comparator<File> comparator) {
		Assert.notNull(synchronizer, "synchronizer must not be null");
		this.synchronizer = synchronizer;
		if (comparator == null) {
			this.fileSource = new FileReadingMessageSource();
		}
		else {
			this.fileSource = new FileReadingMessageSource(comparator);
		}
	}


	public void setAutoCreateLocalDirectory(boolean autoCreateLocalDirectory) {
		this.autoCreateLocalDirectory = autoCreateLocalDirectory;
	}

	public void setLocalDirectory(File localDirectory) {
		this.localDirectory = localDirectory;
	}

	/**
	 * A {@link FileListFilter} used to determine which files will generate messages
	 * after they have been synchronized. It will be combined with a filter that
	 * will prevent accessing files that are in the process of being synchronized
	 * (files having the {@link AbstractInboundFileSynchronizer#getTemporaryFileSuffix()}).
	 * <p>
	 * The default is an {@link AcceptOnceFileListFilter} which filters duplicate file
	 * names (processed during the current execution).
	 *
	 * @param localFileListFilter The local file list filter.
	 */
	public void setLocalFilter(FileListFilter<File> localFileListFilter) {
		this.localFileListFilter = localFileListFilter;
	}

	/**
	 * Switch the local {@link FileReadingMessageSource} to use its internal
	 * {@code FileReadingMessageSource.WatchServiceDirectoryScanner}.
	 * @param useWatchService the {@code boolean} flag to switch to
	 * {@code FileReadingMessageSource.WatchServiceDirectoryScanner} on {@code true}.
	 * @since 5.0
	 */
	public void setUseWatchService(boolean useWatchService) {
		this.fileSource.setUseWatchService(useWatchService);
		if (useWatchService) {
			this.fileSource.setWatchEvents(FileReadingMessageSource.WatchEventType.CREATE,
					FileReadingMessageSource.WatchEventType.MODIFY,
					FileReadingMessageSource.WatchEventType.DELETE);
		}
	}

	/**
	 * Switch the local {@link FileReadingMessageSource} to use a custom
	 * {@link DirectoryScanner}.
	 * @param scanner the {@link DirectoryScanner} to use.
	 * @since 5.0
	 */
	public void setScanner(DirectoryScanner scanner) {
		this.fileSource.setScanner(scanner);
		this.scannerExplicitlySet = true;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		super.afterPropertiesSet();
		Assert.notNull(this.localDirectory, "localDirectory must not be null");
		try {
			if (!this.localDirectory.exists()) {
				if (this.autoCreateLocalDirectory) {
					if (logger.isDebugEnabled()) {
						logger.debug("The '" + this.localDirectory + "' directory doesn't exist; Will create.");
					}
					this.localDirectory.mkdirs();
				}
				else {
					throw new FileNotFoundException(this.localDirectory.getName());
				}
			}
			this.fileSource.setDirectory(this.localDirectory);
			if (this.localFileListFilter == null) {
				this.localFileListFilter = new FileSystemPersistentAcceptOnceFileListFilter(
						new SimpleMetadataStore(), getComponentName());
			}
			FileListFilter<File> filter = buildFilter();
			if (this.scannerExplicitlySet) {
				Assert.state(!this.fileSource.isUseWatchService(),
						"'useWatchService' and 'scanner' are mutually exclusive.");
				this.fileSource.getScanner().setFilter(filter);
			}
			else if (!this.fileSource.isUseWatchService()) {
				DirectoryScanner directoryScanner = new DefaultDirectoryScanner();
				directoryScanner.setFilter(filter);
				this.fileSource.setScanner(directoryScanner);
			}
			else {
				this.fileSource.setFilter(filter);
			}
			if (this.getBeanFactory() != null) {
				this.fileSource.setBeanFactory(this.getBeanFactory());
			}
			this.fileSource.afterPropertiesSet();
			this.synchronizer.afterPropertiesSet();
		}
		catch (RuntimeException e) {
			throw e;
		}
		catch (Exception e) {
			throw new BeanInitializationException("Failure during initialization of MessageSource for: "
					+ this.getClass(), e);
		}
	}

	@Override
	public void start() {
		this.running = true;
		this.fileSource.start();
	}

	@Override
	public void stop() {
		this.running = false;
		try {
			this.fileSource.stop();
			this.synchronizer.close();
		}
		catch (IOException e) {
			logger.error("Error closing synchronizer", e);
		}
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}

	/**
	 * Polls from the file source. If the result is not null, it will be returned.
	 * If the result is null, it attempts to sync up with the remote directory to populate the file source.
	 * At most, maxFetchSize files will be fetched.
	 * Then, it polls the file source again and returns the result, whether or not it is null.
	 * @param maxFetchSize the maximum files to fetch.
	 */
	@Override
	public final Message<File> doReceive(int maxFetchSize) {
		Message<File> message = this.fileSource.receive();
		if (message == null) {
			this.synchronizer.synchronizeToLocalDirectory(this.localDirectory, maxFetchSize);
			message = this.fileSource.receive();
		}
		return message;
	}

	private FileListFilter<File> buildFilter() {
		Pattern completePattern = Pattern.compile("^.*(?<!" + this.synchronizer.getTemporaryFileSuffix() + ")$");
		return new CompositeFileListFilter<File>(Arrays.asList(
				this.localFileListFilter,
				new RegexPatternFileListFilter(completePattern)));
	}

}
