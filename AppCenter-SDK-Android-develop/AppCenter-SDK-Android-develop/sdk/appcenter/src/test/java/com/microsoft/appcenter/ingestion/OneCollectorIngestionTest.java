package com.microsoft.appcenter.ingestion;

import android.content.Context;

import com.microsoft.appcenter.Constants;
import com.microsoft.appcenter.http.DefaultHttpClient;
import com.microsoft.appcenter.http.HttpClient;
import com.microsoft.appcenter.http.HttpClientNetworkStateHandler;
import com.microsoft.appcenter.http.HttpUtils;
import com.microsoft.appcenter.http.ServiceCall;
import com.microsoft.appcenter.http.ServiceCallback;
import com.microsoft.appcenter.ingestion.models.Log;
import com.microsoft.appcenter.ingestion.models.LogContainer;
import com.microsoft.appcenter.ingestion.models.json.LogSerializer;
import com.microsoft.appcenter.ingestion.models.one.CommonSchemaLog;
import com.microsoft.appcenter.ingestion.models.one.Extensions;
import com.microsoft.appcenter.ingestion.models.one.ProtocolExtension;
import com.microsoft.appcenter.utils.AppCenterLog;
import com.microsoft.appcenter.utils.TicketCache;
import com.microsoft.appcenter.utils.UUIDUtils;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.rule.PowerMockRule;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static com.microsoft.appcenter.BuildConfig.VERSION_NAME;
import static com.microsoft.appcenter.http.DefaultHttpClient.METHOD_POST;
import static com.microsoft.appcenter.ingestion.OneCollectorIngestion.TICKETS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyMapOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.contains;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.notNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.verifyStatic;
import static org.powermock.api.mockito.PowerMockito.whenNew;

@SuppressWarnings("unused")
@PrepareForTest({
        OneCollectorIngestion.class,
        AppCenterLog.class,
        JSONObject.class
})
public class OneCollectorIngestionTest {

    @Rule
    public PowerMockRule mPowerMockRule = new PowerMockRule();

    @Captor
    private ArgumentCaptor<Map<String, String>> mHeadersCaptor;

    @Before
    public void setUp() throws Exception {
        TicketCache.clear();

        /* Test JSONObject implementation. */
        JSONObject json = mock(JSONObject.class);
        whenNew(JSONObject.class).withAnyArguments().thenReturn(json);
        final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        when(json.put(key.capture(), value.capture())).thenReturn(json);
        when(json.length()).thenAnswer(new Answer<Integer>() {

            @Override
            public Integer answer(InvocationOnMock invocation) {
                return key.getAllValues().size();
            }
        });
        when(json.toString()).thenAnswer(new Answer<String>() {

            @Override
            public String answer(InvocationOnMock invocation) {
                int length = key.getAllValues().size();
                String[] pairs = new String[length];
                for (int i = 0; i < length; i++) {
                    pairs[i] = String.format("\"%s\":\"%s\"", key.getAllValues().get(i), value.getAllValues().get(i));
                }
                return String.format("{%s}", String.join(",", pairs));
            }
        });
    }

    @After
    public void tearDown() {
        Constants.APPLICATION_DEBUGGABLE = false;
    }

    @Test
    public void sendAsync() throws Exception {

        /* Mock time */
        mockStatic(System.class);
        when(System.currentTimeMillis()).thenReturn(1234L);

        /* Build some payload. */
        Extensions ext = new Extensions() {{
            setProtocol(new ProtocolExtension());
        }};
        final CommonSchemaLog log1 = mock(CommonSchemaLog.class);
        when(log1.getExt()).thenReturn(ext);
        when(log1.getTransmissionTargetTokens()).thenReturn(Collections.singleton("token1"));
        final CommonSchemaLog log2 = mock(CommonSchemaLog.class);
        when(log2.getExt()).thenReturn(ext);
        when(log2.getTransmissionTargetTokens()).thenReturn(new HashSet<>(Arrays.asList("token2", "token3")));
        LogContainer container = new LogContainer() {{
            setLogs(new ArrayList<Log>() {{
                add(log1);
                add(log2);
            }});
        }};
        LogSerializer serializer = mock(LogSerializer.class);
        when(serializer.serializeLog(log1)).thenReturn("mockPayload1");
        when(serializer.serializeLog(log2)).thenReturn("mockPayload2");

        /* Configure mock HTTP. */
        HttpClientNetworkStateHandler httpClient = mock(HttpClientNetworkStateHandler.class);
        whenNew(HttpClientNetworkStateHandler.class).withAnyArguments().thenReturn(httpClient);
        ServiceCall call = mock(ServiceCall.class);
        ArgumentCaptor<HttpClient.CallTemplate> callTemplate = ArgumentCaptor.forClass(HttpClient.CallTemplate.class);
        when(httpClient.callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), callTemplate.capture(), any(ServiceCallback.class))).thenReturn(call);

        /* Test calling code. */
        OneCollectorIngestion ingestion = new OneCollectorIngestion(mock(Context.class), serializer);
        ingestion.setLogUrl("http://mock");
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        assertEquals(call, ingestion.sendAsync(null, null, container, serviceCallback));

        /* Verify call to http client. */
        HashMap<String, String> expectedHeaders = new HashMap<>();
        expectedHeaders.put(OneCollectorIngestion.API_KEY, "token1,token2,token3");
        expectedHeaders.put(OneCollectorIngestion.CLIENT_VERSION_KEY, String.format("ACS-Android-Java-no-%s-no", VERSION_NAME));
        expectedHeaders.put(OneCollectorIngestion.UPLOAD_TIME_KEY, "1234");
        expectedHeaders.put(DefaultHttpClient.CONTENT_TYPE_KEY, "application/x-json-stream; charset=utf-8");
        verify(httpClient).callAsync(eq("http://mock"), eq(METHOD_POST), eq(expectedHeaders), notNull(HttpClient.CallTemplate.class), eq(serviceCallback));
        assertNotNull(callTemplate.getValue());
        assertEquals("mockPayload1\nmockPayload2\n", callTemplate.getValue().buildRequestBody());

        /* Verify close. */
        ingestion.close();
        verify(httpClient).close();

        /* Verify reopen. */
        ingestion.reopen();
        verify(httpClient).reopen();
    }

    @Test
    public void passTicketsDebug() throws Exception {
        Constants.APPLICATION_DEBUGGABLE = true;
        Map<String, String> headers = passTickets();
        assertEquals("true", headers.get(OneCollectorIngestion.STRICT));
    }

    @Test
    public void passTicketsRelease() throws Exception {
        Map<String, String> headers = passTickets();
        assertNull(headers.get(OneCollectorIngestion.STRICT));
    }

    private Map<String, String> passTickets() throws Exception {

        /* Build some payload. */
        final CommonSchemaLog log1 = mock(CommonSchemaLog.class);
        final CommonSchemaLog log2 = mock(CommonSchemaLog.class);
        final List<String> ticketKeys = new ArrayList<String>() {{
            add("key1");
            add("key2");
            add(null);
        }};
        TicketCache.putTicket("key2", "value2");
        Extensions ext1 = new Extensions() {{
            setProtocol(new ProtocolExtension() {{
                setTicketKeys(ticketKeys);
            }});
        }};
        Extensions ext2 = new Extensions() {{
            setProtocol(new ProtocolExtension());
        }};
        when(log1.getExt()).thenReturn(ext1);
        when(log2.getExt()).thenReturn(ext2);
        LogContainer container = new LogContainer() {{
            setLogs(new ArrayList<Log>() {{
                add(log1);
                add(log2);
            }});
        }};

        /* Configure mock HTTP. */
        HttpClientNetworkStateHandler httpClient = mock(HttpClientNetworkStateHandler.class);
        whenNew(HttpClientNetworkStateHandler.class).withAnyArguments().thenReturn(httpClient);
        ServiceCall call = mock(ServiceCall.class);
        when(httpClient.callAsync(anyString(), anyString(), mHeadersCaptor.capture(), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).thenReturn(call);

        /* Verify call to http client. */
        LogSerializer serializer = mock(LogSerializer.class);
        OneCollectorIngestion ingestion = new OneCollectorIngestion(mock(Context.class), serializer);
        ingestion.setLogUrl("http://mock");
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        assertEquals(call, ingestion.sendAsync(null, null, container, serviceCallback));

        /* Verify call to http client. */
        Map<String, String> headers = mHeadersCaptor.getValue();
        assertTrue(headers.containsKey(TICKETS));
        assertEquals("{\"key2\":\"value2\"}", headers.get(TICKETS));
        return headers;
    }

    @Test
    public void ticketsFailToSerialize() throws Exception {

        /* Build some payload. */
        final CommonSchemaLog log1 = mock(CommonSchemaLog.class);
        final List<String> ticketKeys = new ArrayList<String>() {{
            add("key1");
        }};
        TicketCache.putTicket("key1", "value1");
        Extensions ext1 = new Extensions() {{
            setProtocol(new ProtocolExtension() {{
                setTicketKeys(ticketKeys);
            }});
        }};
        when(log1.getExt()).thenReturn(ext1);
        LogContainer container = new LogContainer() {{
            setLogs(new ArrayList<Log>() {{
                add(log1);
            }});
        }};

        JSONObject ticketJson = mock(JSONObject.class);
        whenNew(JSONObject.class).withNoArguments().thenReturn(ticketJson);
        when(ticketJson.put(anyString(), anyString())).thenThrow(new JSONException("mock"));

        /* Configure mock HTTP. */
        HttpClientNetworkStateHandler httpClient = mock(HttpClientNetworkStateHandler.class);
        whenNew(HttpClientNetworkStateHandler.class).withAnyArguments().thenReturn(httpClient);
        ServiceCall call = mock(ServiceCall.class);
        when(httpClient.callAsync(anyString(), anyString(), mHeadersCaptor.capture(), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).thenReturn(call);

        /* Verify call to http client. */
        LogSerializer serializer = mock(LogSerializer.class);
        OneCollectorIngestion ingestion = new OneCollectorIngestion(mock(Context.class), serializer);
        ingestion.setLogUrl("http://mock");
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        assertEquals(call, ingestion.sendAsync(null, null, container, serviceCallback));

        /* Verify call to http client was made without headers as JSON failed. */
        Map<String, String> headers = mHeadersCaptor.getValue();
        assertFalse(headers.containsKey(TICKETS));
    }

    @Test
    public void failedSerialization() throws Exception {

        /* Build some payload. */
        final CommonSchemaLog log = mock(CommonSchemaLog.class);
        when(log.getExt()).thenReturn(new Extensions() {{
            setProtocol(new ProtocolExtension());
        }});
        LogContainer container = new LogContainer() {{
            setLogs(new ArrayList<Log>() {{
                add(log);
            }});
        }};
        LogSerializer serializer = mock(LogSerializer.class);
        JSONException exception = new JSONException("mock");
        when(serializer.serializeLog(log)).thenThrow(exception);

        /* Configure mock HTTP. */
        HttpClientNetworkStateHandler httpClient = mock(HttpClientNetworkStateHandler.class);
        whenNew(HttpClientNetworkStateHandler.class).withAnyArguments().thenReturn(httpClient);
        ServiceCall call = mock(ServiceCall.class);
        ArgumentCaptor<HttpClient.CallTemplate> callTemplate = ArgumentCaptor.forClass(HttpClient.CallTemplate.class);
        when(httpClient.callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), callTemplate.capture(), any(ServiceCallback.class))).thenReturn(call);

        /* Test calling code. */
        OneCollectorIngestion ingestion = new OneCollectorIngestion(mock(Context.class), serializer);
        ingestion.setLogUrl("http://mock");
        ServiceCallback serviceCallback = mock(ServiceCallback.class);
        assertEquals(call, ingestion.sendAsync(null, null, container, serviceCallback));

        /* Verify call to http client. */
        assertNotNull(callTemplate.getValue());
        try {
            callTemplate.getValue().buildRequestBody();
            Assert.fail("Expected json exception");
        } catch (JSONException ignored) {
        }

        /* Verify close. */
        ingestion.close();
        verify(httpClient).close();
    }

    @Test
    public void onBeforeCalling() throws Exception {

        /* Mock instances. */
        URL url = new URL("http://mock/path/file");
        String apiKeys = UUIDUtils.randomUUID().toString();
        String obfuscatedApiKeys = HttpUtils.hideApiKeys(apiKeys);
        String tickets = "{'hash':'secretValue'}";
        String obfuscatedTickets = HttpUtils.hideTickets(tickets);
        Map<String, String> headers = new HashMap<>();
        headers.put("Another-Header", "Another-Value");
        HttpClient.CallTemplate callTemplate = getCallTemplate();
        AppCenterLog.setLogLevel(android.util.Log.VERBOSE);
        mockStatic(AppCenterLog.class);

        /* Call onBeforeCalling with parameters. */
        callTemplate.onBeforeCalling(url, headers);

        /* Verify url log. */
        verifyStatic();
        AppCenterLog.verbose(anyString(), contains(url.toString()));

        /* Verify header log. */
        for (Map.Entry<String, String> header : headers.entrySet()) {
            verifyStatic();
            AppCenterLog.verbose(anyString(), contains(header.getValue()));
        }

        /* Put app secret to header. */
        headers.put(OneCollectorIngestion.API_KEY, apiKeys);
        callTemplate.onBeforeCalling(url, headers);

        /* Verify api key is in log. */
        verifyStatic();
        AppCenterLog.verbose(anyString(), contains(obfuscatedApiKeys));

        /* Add ticket to header and check the same way as api key. */
        headers.put(OneCollectorIngestion.TICKETS, tickets);
        callTemplate.onBeforeCalling(url, headers);
        verifyStatic();
        AppCenterLog.verbose(anyString(), contains(obfuscatedTickets));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void onBeforeCallingWithAnotherLogLevel() throws Exception {

        /* Mock instances. */
        String apiKey = UUIDUtils.randomUUID().toString();
        HttpClient.CallTemplate callTemplate = getCallTemplate();

        /* Change log level. */
        AppCenterLog.setLogLevel(android.util.Log.WARN);

        /* Call onBeforeCalling with parameters. */
        callTemplate.onBeforeCalling(mock(URL.class), mock(Map.class));

        /* Verify. */
        verifyStatic(never());
        AppCenterLog.verbose(anyString(), anyString());
    }

    private HttpClient.CallTemplate getCallTemplate() throws Exception {

        /* Configure mock HTTP to get an instance of IngestionCallTemplate. */
        ServiceCall call = mock(ServiceCall.class);
        ArgumentCaptor<HttpClient.CallTemplate> callTemplate = ArgumentCaptor.forClass(HttpClient.CallTemplate.class);
        HttpClientNetworkStateHandler httpClient = mock(HttpClientNetworkStateHandler.class);
        whenNew(HttpClientNetworkStateHandler.class).withAnyArguments().thenReturn(httpClient);
        when(httpClient.callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), callTemplate.capture(), any(ServiceCallback.class))).thenReturn(call);
        OneCollectorIngestion ingestion = new OneCollectorIngestion(mock(Context.class), mock(LogSerializer.class));
        ingestion.setLogUrl("http://mock");
        assertEquals(call, ingestion.sendAsync(null, null, mock(LogContainer.class), mock(ServiceCallback.class)));
        return callTemplate.getValue();
    }
}