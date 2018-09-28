package com.microsoft.appcenter.channel;

import android.content.Context;

import com.microsoft.appcenter.ingestion.AppCenterIngestion;
import com.microsoft.appcenter.ingestion.Ingestion;
import com.microsoft.appcenter.ingestion.models.Log;
import com.microsoft.appcenter.persistence.Persistence;
import com.microsoft.appcenter.utils.UUIDUtils;

import org.junit.Test;
import org.mockito.Matchers;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.spy;

public class DefaultChannelOtherOperationsTest extends AbstractDefaultChannelTest {

    @Test
    @SuppressWarnings("unchecked")
    public void setLogUrl() {
        Ingestion ingestion = mock(Ingestion.class);
        DefaultChannel channel = new DefaultChannel(mock(Context.class), UUIDUtils.randomUUID().toString(), mock(Persistence.class), ingestion, mAppCenterHandler);
        String logUrl = "http://mockUrl";
        channel.setLogUrl(logUrl);
        verify(ingestion).setLogUrl(logUrl);
    }

    @Test
    public void logCallbacks() {

        @SuppressWarnings("ConstantConditions")
        DefaultChannel channel = new DefaultChannel(mock(Context.class), UUIDUtils.randomUUID().toString(), mock(Persistence.class), mock(AppCenterIngestion.class), mAppCenterHandler);
        channel.addGroup(TEST_GROUP, 50, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, null, null);
        Channel.Listener listener = spy(new AbstractChannelListener());
        channel.addListener(listener);

        /* Check enqueue. */
        Log log = mock(Log.class);
        channel.enqueue(log, TEST_GROUP);
        verify(listener).onPreparingLog(log, TEST_GROUP);
        verify(listener).onPreparedLog(log, TEST_GROUP);
        verify(listener).shouldFilter(log);
        verifyNoMoreInteractions(listener);

        /* Check clear. */
        channel.clear(TEST_GROUP);
        verify(listener).onClear(TEST_GROUP);
        verifyNoMoreInteractions(listener);

        /* Check no more calls after removing listener. */
        log = mock(Log.class);
        channel.removeListener(listener);
        channel.enqueue(log, TEST_GROUP);
        verifyNoMoreInteractions(listener);
    }

    @Test
    public void clear() {
        Persistence mockPersistence = mock(Persistence.class);
        DefaultChannel channel = new DefaultChannel(mock(Context.class), UUIDUtils.randomUUID().toString(), mockPersistence, mock(AppCenterIngestion.class), mAppCenterHandler);
        channel.addGroup(TEST_GROUP, 50, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, null, null);

        /* Clear an existing channel. */
        channel.clear(TEST_GROUP);
        verify(mockPersistence).deleteLogs(TEST_GROUP);
        reset(mockPersistence);

        /* Clear a non-existing channel. */
        channel.clear(TEST_GROUP + "2");
        verify(mockPersistence, never()).deleteLogs(anyString());
    }

    @Test
    public void shutdown() {
        Persistence mockPersistence = mock(Persistence.class);
        AppCenterIngestion mockIngestion = mock(AppCenterIngestion.class);
        Channel.GroupListener mockListener = mock(Channel.GroupListener.class);
        when(mockPersistence.getLogs(any(String.class), anyListOf(String.class), anyInt(), Matchers.<List<Log>>any()))
                .then(getGetLogsAnswer(1));
        DefaultChannel channel = new DefaultChannel(mock(Context.class), UUIDUtils.randomUUID().toString(), mockPersistence, mockIngestion, mAppCenterHandler);
        channel.addGroup(TEST_GROUP, 1, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, null, mockListener);

        /* Enqueuing 1 event. */
        channel.enqueue(mock(Log.class), TEST_GROUP);
        verify(mockListener).onBeforeSending(notNull(Log.class));

        channel.shutdown();
        verify(mockListener, never()).onFailure(any(Log.class), any(Exception.class));
        verify(mockPersistence).clearPendingLogState();
    }

    @Test
    public void filter() throws Persistence.PersistenceException {

        /* Given a mock channel. */
        Persistence persistence = mock(Persistence.class);

        @SuppressWarnings("ConstantConditions")
        DefaultChannel channel = new DefaultChannel(mock(Context.class), UUIDUtils.randomUUID().toString(), persistence, mock(AppCenterIngestion.class), mAppCenterHandler);
        channel.addGroup(TEST_GROUP, 50, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, null, null);

        /* Given we add mock listeners. */
        Channel.Listener listener1 = mock(Channel.Listener.class);
        channel.addListener(listener1);
        Channel.Listener listener2 = mock(Channel.Listener.class);
        channel.addListener(listener2);

        /* Given 1 log. */
        {
            /* Given the second listener filtering out logs. */
            Log log = mock(Log.class);
            when(listener2.shouldFilter(log)).thenReturn(true);

            /* When we enqueue that log. */
            channel.enqueue(log, TEST_GROUP);

            /* Then except the following. behaviors. */
            verify(listener1).onPreparingLog(log, TEST_GROUP);
            verify(listener1).shouldFilter(log);
            verify(listener2).onPreparingLog(log, TEST_GROUP);
            verify(listener2).shouldFilter(log);
            verify(persistence, never()).putLog(TEST_GROUP, log);
        }

        /* Given 1 log. */
        {
            /* Given the first listener filtering out logs. */
            Log log = mock(Log.class);
            when(listener1.shouldFilter(log)).thenReturn(true);
            when(listener2.shouldFilter(log)).thenReturn(false);

            /* When we enqueue that log. */
            channel.enqueue(log, TEST_GROUP);

            /* Then except the following. behaviors. */
            verify(listener1).onPreparingLog(log, TEST_GROUP);
            verify(listener1).shouldFilter(log);
            verify(listener2).onPreparingLog(log, TEST_GROUP);

            /* Second listener skipped since first listener filtered out. */
            verify(listener2, never()).shouldFilter(log);
            verify(persistence, never()).putLog(TEST_GROUP, log);
        }

        /* Given 1 log. */
        {
            /* Given no listener filtering out logs. */
            Log log = mock(Log.class);
            when(listener1.shouldFilter(log)).thenReturn(false);
            when(listener2.shouldFilter(log)).thenReturn(false);

            /* When we enqueue that log. */
            channel.enqueue(log, TEST_GROUP);

            /* Then except the following. behaviors. */
            verify(listener1).onPreparingLog(log, TEST_GROUP);
            verify(listener1).shouldFilter(log);
            verify(listener2).onPreparingLog(log, TEST_GROUP);
            verify(listener2).shouldFilter(log);
            verify(persistence).putLog(TEST_GROUP, log);
        }
    }

    @Test
    public void groupCallbacks() {
        Persistence persistence = mock(Persistence.class);
        Ingestion ingestion = mock(Ingestion.class);
        Channel.Listener listener = spy(new AbstractChannelListener());
        DefaultChannel channel = new DefaultChannel(mock(Context.class), UUIDUtils.randomUUID().toString(), persistence, ingestion, mAppCenterHandler);
        channel.addListener(listener);
        Channel.GroupListener groupListener = mock(Channel.GroupListener.class);
        channel.addGroup(TEST_GROUP, 50, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, null, groupListener);
        verify(listener).onGroupAdded(TEST_GROUP, groupListener);
        channel.pauseGroup(TEST_GROUP, null);
        verify(listener).onPaused(TEST_GROUP, null);
        channel.pauseGroup(TEST_GROUP, "token");
        verify(listener).onPaused(TEST_GROUP, "token");
        channel.resumeGroup(TEST_GROUP, null);
        verify(listener).onResumed(TEST_GROUP, null);
        channel.resumeGroup(TEST_GROUP, "token");
        verify(listener).onResumed(TEST_GROUP, "token");
        channel.removeGroup(TEST_GROUP);
        verify(listener).onGroupRemoved(TEST_GROUP);
    }

    @Test
    public void checkSetStorageSizeForwarding() {

        /* The real Android test for checking size is in StorageHelperAndroidTest. */
        Persistence persistence = mock(Persistence.class);
        when(persistence.setMaxStorageSize(anyLong())).thenReturn(true).thenReturn(false);
        DefaultChannel channel = new DefaultChannel(mock(Context.class), UUIDUtils.randomUUID().toString(), persistence, mock(Ingestion.class), mAppCenterHandler);

        /* Just checks calls are forwarded to the low level database layer. */
        assertTrue(channel.setMaxStorageSize(20480));
        assertFalse(channel.setMaxStorageSize(2));
    }
}
