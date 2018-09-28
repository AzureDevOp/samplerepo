package com.microsoft.appcenter.channel;

import android.content.Context;

import com.microsoft.appcenter.http.ServiceCallback;
import com.microsoft.appcenter.ingestion.AppCenterIngestion;
import com.microsoft.appcenter.ingestion.OneCollectorIngestion;
import com.microsoft.appcenter.ingestion.models.Log;
import com.microsoft.appcenter.ingestion.models.LogContainer;
import com.microsoft.appcenter.persistence.Persistence;
import com.microsoft.appcenter.utils.UUIDUtils;

import org.junit.Test;

import java.util.Collections;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.spy;

public class DefaultChannelPauseResumeTest extends AbstractDefaultChannelTest {

    @Test
    public void pauseResumeGroup() throws Persistence.PersistenceException {
        Persistence mockPersistence = mock(Persistence.class);
        AppCenterIngestion mockIngestion = mock(AppCenterIngestion.class);
        Channel.GroupListener mockListener = mock(Channel.GroupListener.class);

        when(mockPersistence.getLogs(any(String.class), anyListOf(String.class), anyInt(), anyListOf(Log.class))).then(getGetLogsAnswer(50));
        when(mockIngestion.sendAsync(anyString(), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class))).then(getSendAsyncAnswer());

        DefaultChannel channel = new DefaultChannel(mock(Context.class), UUIDUtils.randomUUID().toString(), mockPersistence, mockIngestion, mAppCenterHandler);
        channel.addGroup(TEST_GROUP, 50, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, null, mockListener);
        assertFalse(channel.getGroupState(TEST_GROUP).mPaused);

        /* Pause group. */
        channel.pauseGroup(TEST_GROUP, null);
        assertTrue(channel.getGroupState(TEST_GROUP).mPaused);

        /* Enqueue a log. */
        for (int i = 0; i < 50; i++) {
            channel.enqueue(mock(Log.class), TEST_GROUP);
        }
        verify(mAppCenterHandler, never()).postDelayed(any(Runnable.class), eq(BATCH_TIME_INTERVAL));

        /* 50 logs are persisted but never being sent to Ingestion. */
        assertEquals(50, channel.getCounter(TEST_GROUP));
        verify(mockPersistence, times(50)).putLog(eq(TEST_GROUP), any(Log.class));
        verify(mockIngestion, never()).sendAsync(anyString(), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));
        verify(mockPersistence, never()).deleteLogs(any(String.class), any(String.class));
        verify(mockListener, never()).onBeforeSending(any(Log.class));
        verify(mockListener, never()).onSuccess(any(Log.class));

        /* The counter should still be 50 now as we did NOT send data. */
        assertEquals(50, channel.getCounter(TEST_GROUP));

        /* Resume group. */
        channel.resumeGroup(TEST_GROUP, null);
        assertFalse(channel.getGroupState(TEST_GROUP).mPaused);

        /* Verify channel starts sending logs. */
        verify(mAppCenterHandler, never()).postDelayed(any(Runnable.class), eq(BATCH_TIME_INTERVAL));
        verify(mockIngestion).sendAsync(anyString(), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));
        verify(mockPersistence).deleteLogs(any(String.class), any(String.class));
        verify(mockListener, times(50)).onBeforeSending(any(Log.class));
        verify(mockListener, times(50)).onSuccess(any(Log.class));

        /* The counter should be 0 now as we sent data. */
        assertEquals(0, channel.getCounter(TEST_GROUP));
    }

    @Test
    public void pauseGroupTwice() {
        DefaultChannel channel = spy(new DefaultChannel(mock(Context.class), UUIDUtils.randomUUID().toString(), mock(Persistence.class), mock(AppCenterIngestion.class), mAppCenterHandler));
        channel.addGroup(TEST_GROUP, 50, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, null, mock(Channel.GroupListener.class));
        assertFalse(channel.getGroupState(TEST_GROUP).mPaused);

        /* Pause group twice. */
        channel.pauseGroup(TEST_GROUP, null);
        assertTrue(channel.getGroupState(TEST_GROUP).mPaused);
        channel.pauseGroup(TEST_GROUP, null);
        assertTrue(channel.getGroupState(TEST_GROUP).mPaused);

        /* Verify the group is paused only once. */
        verify(channel).cancelTimer(any(DefaultChannel.GroupState.class));
    }

    @Test
    public void resumeGroupWhileNotPaused() {
        DefaultChannel channel = spy(new DefaultChannel(mock(Context.class), UUIDUtils.randomUUID().toString(), mock(Persistence.class), mock(AppCenterIngestion.class), mAppCenterHandler));
        channel.addGroup(TEST_GROUP, 50, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, null, mock(Channel.GroupListener.class));
        verify(channel).checkPendingLogs(eq(TEST_GROUP));
        assertFalse(channel.getGroupState(TEST_GROUP).mPaused);

        /* Resume group. */
        channel.resumeGroup(TEST_GROUP, null);
        assertFalse(channel.getGroupState(TEST_GROUP).mPaused);

        /* Verify resumeGroup doesn't resume the group while un-paused.  */
        verify(channel).checkPendingLogs(eq(TEST_GROUP));
    }

    @Test
    public void pauseResumeTargetToken() throws Persistence.PersistenceException {

        /* Mock database and ingestion. */
        Persistence persistence = mock(Persistence.class);
        OneCollectorIngestion ingestion = mock(OneCollectorIngestion.class);

        /* Create a channel with a log group that send logs 1 by 1. */
        AppCenterIngestion appCenterIngestion = mock(AppCenterIngestion.class);
        DefaultChannel channel = new DefaultChannel(mock(Context.class), UUIDUtils.randomUUID().toString(), persistence, appCenterIngestion, mAppCenterHandler);
        channel.addGroup(TEST_GROUP, 1, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, ingestion, null);

        /* Reset to verify further interactions besides initial check after adding group. */
        reset(persistence);

        /* Pause token. */
        String targetToken = "iKey-apiKey";
        channel.pauseGroup(TEST_GROUP, targetToken);

        /* Mock the database to return logs now. */
        when(persistence.getLogs(any(String.class), anyListOf(String.class), anyInt(), anyListOf(Log.class))).then(getGetLogsAnswer(1));
        when(persistence.countLogs(TEST_GROUP)).thenReturn(1);

        /* Enqueue a log. */
        Log log = mock(Log.class);
        when(log.getTransmissionTargetTokens()).thenReturn(Collections.singleton(targetToken));
        channel.enqueue(log, TEST_GROUP);

        /* Verify persisted but not incrementing and checking logs. */
        verify(persistence).putLog(TEST_GROUP, log);
        assertEquals(0, channel.getCounter(TEST_GROUP));
        verify(persistence, never()).countLogs(TEST_GROUP);
        verify(ingestion, never()).sendAsync(anyString(), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));

        /* Pausing a second time has no effect. */
        channel.pauseGroup(TEST_GROUP, targetToken);
        verify(persistence, never()).countLogs(TEST_GROUP);

        /* Enqueueing a log from another transmission target works. */
        Log otherLog = mock(Log.class);
        when(otherLog.getTransmissionTargetTokens()).thenReturn(Collections.singleton("iKey2-apiKey2"));
        channel.enqueue(otherLog, TEST_GROUP);
        verify(ingestion).sendAsync(anyString(), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));
        reset(ingestion);

        /* Resume token. */
        channel.resumeGroup(TEST_GROUP, targetToken);
        verify(ingestion).sendAsync(anyString(), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));

        /* Sending more logs works now. */
        reset(ingestion);
        channel.enqueue(log, TEST_GROUP);
        verify(ingestion).sendAsync(anyString(), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));

        /* Calling resume a second time has 0 effect. */
        reset(persistence);
        reset(ingestion);
        channel.resumeGroup(TEST_GROUP, targetToken);
        verifyZeroInteractions(persistence);
        verifyZeroInteractions(ingestion);

        /* AppCenter ingestion never used. */
        verify(appCenterIngestion, never()).sendAsync(anyString(), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));
    }

    @Test
    public void pauseGroupPauseTargetResumeGroupResumeTarget() throws Persistence.PersistenceException {

        /* Mock database and ingestion. */
        Persistence persistence = mock(Persistence.class);
        OneCollectorIngestion ingestion = mock(OneCollectorIngestion.class);

        /* Create a channel with a log group that send logs 1 by 1. */
        DefaultChannel channel = new DefaultChannel(mock(Context.class), UUIDUtils.randomUUID().toString(), persistence, mock(AppCenterIngestion.class), mAppCenterHandler);
        channel.addGroup(TEST_GROUP, 1, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, ingestion, null);

        /* Pause group first. */
        channel.pauseGroup(TEST_GROUP, null);

        /* Pause token. */
        String targetToken = "iKey-apiKey";
        channel.pauseGroup(TEST_GROUP, targetToken);

        /* Mock the database to return logs now. */
        when(persistence.getLogs(any(String.class), anyListOf(String.class), anyInt(), anyListOf(Log.class))).then(getGetLogsAnswer(1));
        when(persistence.countLogs(TEST_GROUP)).thenReturn(1);

        /* Enqueue a log. */
        Log log = mock(Log.class);
        when(log.getTransmissionTargetTokens()).thenReturn(Collections.singleton(targetToken));
        channel.enqueue(log, TEST_GROUP);

        /* Verify persisted but not incrementing and checking logs. */
        verify(persistence).putLog(TEST_GROUP, log);
        assertEquals(0, channel.getCounter(TEST_GROUP));
        verify(ingestion, never()).sendAsync(anyString(), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));

        /* Resume group should not send the log. */
        channel.resumeGroup(TEST_GROUP, null);
        verify(ingestion, never()).sendAsync(anyString(), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));

        /* Resume token, send the log now. */
        channel.resumeGroup(TEST_GROUP, targetToken);
        verify(ingestion).sendAsync(anyString(), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));
    }
}
