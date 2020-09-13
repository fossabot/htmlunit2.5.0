/*
 * Copyright (c) 2002-2020 Gargoyle Software Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gargoylesoftware.htmlunit.javascript.host.xml;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.HttpStatus;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;

import com.gargoylesoftware.htmlunit.BrowserRunner;
import com.gargoylesoftware.htmlunit.BrowserRunner.Alerts;
import com.gargoylesoftware.htmlunit.WebDriverTestCase;
import com.gargoylesoftware.htmlunit.util.MimeType;

/**
 * Tests for the LifeCycle events for XMLHttpRequests.
 * readystatechange
 * loadstart - whenever the loading is started. will always have a payload of 0.
 * loadend - whenever the loading is finished. Will be triggered after error as well.
 * progress - periodic updates that the transfer is still in progress. will only be triggered in async requests.
 * abort - aborts the scheduled request. will only be triggerd in async requests.
 * error - on network errors. server status will be ignored for this.
 * timeout - when the request is terminated because of the timeout
 * (only available in async requests, otherwise xhr.send will fail)
 *
 * The tests are split between sync (full-event cycle test) and async (each event is tested on it's own).
 * This is mainly done because we cannot reliably handle the amount & speed of the alerts if everything is
 * executed together (Chrome did work in tests, FF & IE did not).
 *
 * @author Thorsten Wendelmuth
 * @author Ronald Brill
 *
 */
@RunWith(BrowserRunner.class)
public class XMLHttpRequestLifeCycleTest extends WebDriverTestCase {
    private static final String SUCCESS_URL = "/xmlhttprequest/success.html";
    private static final String ERROR_URL = "/xmlhttprequest/error.html";
    private static final String TIMEOUT_URL = "/xmlhttprequest/timeout.html";

    private static final String RETURN_XML = "<xml>\n"
            + "<content>htmlunit</content>\n"
            + "<content>xmlhttpRequest</content>\n"
            + "</xml>";

    private final Map<String, Class<? extends Servlet>> servlets_ = new HashMap<>();

    private enum State {
        LOAD_START("loadstart"), LOAD("load"), LOAD_END("loadend"), PROGRESS("progress"), ERROR("error"),
        ABORT("abort"), READY_STATE_CHANGE("readystatechange"), TIMEOUT("timeout");

        private final String eventName_;

        State(final String eventName) {
            eventName_ = eventName;
        }

        public String getEventName_() {
            return eventName_;
        }
    }

    private enum Mode {
        ASYNC(true, false), SYNC(false, false), ASYNC_ON_KEYWORD(true, true), SYNC_ON_KEYWORD(false, true);

        private final boolean async_;
        private final boolean useOnKeyword_;

        Mode(final boolean async, final boolean useOnKeyword) {
            async_ = async;
            useOnKeyword_ = useOnKeyword;
        }

        public boolean isAsync() {
            return async_;
        }

        public boolean isUseOnKeyword() {
            return useOnKeyword_;
        }
    }

    private enum Execution {
        ONLY_SEND, SEND_ABORT, NETWORK_ERROR, ERROR_500, TIMEOUT
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Integer getWebClientTimeout() {
        return 100;
    }

    @Before
    public void prepareTestingServlets() {
        servlets_.put(SUCCESS_URL, Xml200Servlet.class);
        servlets_.put(ERROR_URL, Xml500Servlet.class);
        servlets_.put(TIMEOUT_URL, XmlTimeoutServlet.class);
    }

    @Test
    @Alerts({"readystatechange_1_0_true", "open-done",
            "readystatechange_4_200_true", "load_4_200_false",
            "loadend_4_200_false", "send-done"})
    public void addEventListener_sync() throws Exception {
        // we can register ourselves for every state here since it's in sync mode and
        // most of them won't fire anyway.
        final WebDriver driver = loadPage2(buildHtml(Mode.SYNC, Execution.ONLY_SEND),
                URL_FIRST, servlets_);
        verifyAlerts(() -> extractLog(driver), String.join("\n", getExpectedAlerts()));
    }

    @Test
    @Alerts(DEFAULT = {"readystatechange_1_0_true", "open-done", "ExceptionThrown"},
            FF = {"readystatechange_1_0_true", "open-done", "readystatechange_4_0_true",
                    "error_4_0_false", "loadend_4_0_false", "ExceptionThrown"},
            FF68 = {"readystatechange_1_0_true", "open-done", "readystatechange_4_0_true",
                    "error_4_0_false", "loadend_4_0_false", "ExceptionThrown"})
    public void addEventListener_sync_networkError() throws Exception {
        // will throw an exception and user is supposed to handle this.
        // That's why we only have one readystatechange callback.
        try {
            loadPage2(buildHtml(Mode.SYNC, Execution.NETWORK_ERROR), URL_FIRST, servlets_);
        }
        catch (final WebDriverException e) {
            if (useRealBrowser()) {
                // we only expect the error to be thrown in htmlunit scenarios.
                throw e;
            }
        }
        finally {
            verifyAlerts(() -> extractLog(getWebDriver()), String.join("\n", getExpectedAlerts()));
        }
    }

    @Test
    @Alerts({"readystatechange_1_0_true", "open-done", "readystatechange_4_500_true",
                "load_4_500_false", "loadend_4_500_false", "send-done"})
    public void addEventListener_sync_Error500() throws Exception {
        final WebDriver driver = loadPage2(buildHtml(Mode.SYNC, Execution.ERROR_500), URL_FIRST,
                servlets_);
        verifyAlerts(() -> extractLog(driver), String.join("\n", getExpectedAlerts()));
    }

    @Test
    @Alerts({"readystatechange_1_0_true", "open-done", "ExceptionThrown"})
    public void addEventListener_sync_timeout() throws Exception {
        // that's invalid. You cannot set timeout for synced requests. Will throw an
        // exception only triggers readystatechange
        try {
            loadPage2(buildHtml(Mode.SYNC, Execution.TIMEOUT), URL_FIRST, servlets_);
        }
        catch (final WebDriverException e) {
            if (useRealBrowser()) {
                // we only expect the error to be thrown in htmlunit scenarios.
                throw e;
            }
        }
        finally {
            verifyAlerts(() -> extractLog(getWebDriver()), String.join("\n", getExpectedAlerts()));
        }
    }

    @Test
    @Alerts(DEFAULT = {"readystatechange_1_0_true", "open-done", "loadstart_1_0_false",
                    "send-done", "readystatechange_2_200_true", "readystatechange_3_200_true",
                    "progress_3_200_false", "readystatechange_4_200_true", "load_4_200_false",
                    "loadend_4_200_false"},
            IE = {"readystatechange_1_0_true", "open-done", "readystatechange_1_0_true",
                    "send-done", "loadstart_1_0_false",
                    "readystatechange_2_200_true", "readystatechange_3_200_true",
                    "progress_3_200_false", "readystatechange_4_200_true", "load_4_200_false",
                    "loadend_4_200_false"})
    public void addEventListener_async() throws Exception {
        final WebDriver driver = loadPage2(buildHtml(Mode.ASYNC, Execution.ONLY_SEND),
                URL_FIRST, servlets_);
        verifyAlerts(() -> extractLog(driver), String.join("\n", getExpectedAlerts()));
    }

    @Test
    @Alerts(DEFAULT = {"readystatechange_1_0_true", "open-done", "loadstart_1_0_false",
                    "send-done", "readystatechange_4_0_true", "abort_4_0", "loadend_4_0_false",
                    "abort-done"},
            IE = {"readystatechange_1_0_true", "open-done", "readystatechange_1_0_true",
                    "send-done", "readystatechange_4_0_true", "abort_4_0",
                    "loadend_4_0_false", "abort-done"})
    public void addEventListener_async_abortTriggered() throws Exception {
        final WebDriver driver = loadPage2(buildHtml(Mode.ASYNC, Execution.SEND_ABORT), URL_FIRST,
                servlets_);
        verifyAlerts(() -> extractLog(driver), String.join("\n", getExpectedAlerts()));
    }

    @Test
    @Alerts(DEFAULT = {"readystatechange_1_0_true", "open-done", "loadstart_1_0_false",
            "send-done", "readystatechange_4_0_true", "error_4_0_false",
            "loadend_4_0_false"},
            IE = {"readystatechange_1_0_true", "open-done", "readystatechange_1_0_true",
                    "send-done", "loadstart_1_0_false", "readystatechange_4_0_true",
                    "error_4_0_false", "loadend_4_0_false"})
    public void addEventListener_async_networkErrorTriggered() throws Exception {
        final WebDriver driver = loadPage2(buildHtml(Mode.ASYNC, Execution.NETWORK_ERROR), URL_FIRST,
                servlets_);
        verifyAlerts(() -> extractLog(driver), String.join("\n", getExpectedAlerts()));
    }

    /**
     * Error 500 on the server side still count as a valid requests for {@link XMLHttpRequest}.
     */
    @Test
    @Alerts(DEFAULT = {"readystatechange_1_0_true", "open-done", "loadstart_1_0_false",
                    "send-done", "readystatechange_2_500_true", "readystatechange_3_500_true",
                    "progress_3_500_false", "readystatechange_4_500_true",
                    "load_4_500_false", "loadend_4_500_false"},
            IE = {"readystatechange_1_0_true", "open-done",
                    "readystatechange_1_0_true", "send-done", "loadstart_1_0_false",
                    "readystatechange_2_500_true", "readystatechange_3_500_true",
                    "progress_3_500_false", "readystatechange_4_500_true",
                    "load_4_500_false", "loadend_4_500_false"})
    public void addEventListener_async_Error500Triggered() throws Exception {
        final WebDriver driver = loadPage2(buildHtml(Mode.ASYNC, Execution.ERROR_500), URL_FIRST,
                servlets_);
        verifyAlerts(() -> extractLog(driver), String.join("\n", getExpectedAlerts()));
    }

    @Test
    @Alerts(DEFAULT = {"readystatechange_1_0_true", "open-done", "loadstart_1_0_false",
                    "send-done", "readystatechange_4_0_true", "timeout_4_0_false",
                    "loadend_4_0_false"},
            IE = {"readystatechange_1_0_true", "open-done", "readystatechange_1_0_true",
                    "send-done", "loadstart_1_0_false", "readystatechange_2_200_true",
                    "readystatechange_4_0_true", "timeout_4_0_false",
                    "loadend_4_0_false"})
    public void addEventListener_async_timeout() throws Exception {
        final WebDriver driver = loadPage2(buildHtml(Mode.ASYNC, Execution.TIMEOUT), URL_FIRST,
                servlets_);
        verifyAlerts(() -> extractLog(driver), String.join("\n", getExpectedAlerts()));
    }

    // same tests as above, but this time we're triggering with the onkeyword.
    @Test
    @Alerts({"readystatechange_1_0_true", "open-done", "readystatechange_4_200_true",
                "load_4_200_false", "loadend_4_200_false", "send-done"})
    public void onKeyWord_sync() throws Exception {
        // we can register ourselves for every state here since it's in sync mode and
        // most of them won't fire anyway.
        final WebDriver driver = loadPage2(buildHtml(Mode.SYNC_ON_KEYWORD, Execution.ONLY_SEND),
                URL_FIRST, servlets_);
        verifyAlerts(() -> extractLog(driver), String.join("\n", getExpectedAlerts()));
    }

    @Test
    @Alerts(DEFAULT = {"readystatechange_1_0_true", "open-done", "ExceptionThrown"},
            FF = {"readystatechange_1_0_true", "open-done", "readystatechange_4_0_true",
                    "error_4_0_false", "loadend_4_0_false", "ExceptionThrown"},
            FF68 = {"readystatechange_1_0_true", "open-done", "readystatechange_4_0_true",
                    "error_4_0_false", "loadend_4_0_false", "ExceptionThrown"})
    public void onKeyWord_sync_networkError() throws Exception {
        // will throw an exception and user is supposed to handle this.
        // That's why we only have one readystatechange callback.
        try {
            loadPage2(buildHtml(Mode.SYNC_ON_KEYWORD, Execution.NETWORK_ERROR), URL_FIRST, servlets_);

        }
        catch (final WebDriverException e) {
            if (useRealBrowser()) {
                // we only expect the error to be thrown in htmlunit scenarios.
                throw e;
            }
        }
        finally {
            verifyAlerts(() -> extractLog(getWebDriver()), String.join("\n", getExpectedAlerts()));
        }
    }

    @Test
    @Alerts({"readystatechange_1_0_true", "open-done", "readystatechange_4_500_true",
            "load_4_500_false", "loadend_4_500_false", "send-done"})
    public void onKeyWord_sync_Error500() throws Exception {
        final WebDriver driver = loadPage2(buildHtml(Mode.SYNC_ON_KEYWORD, Execution.ERROR_500),
                URL_FIRST, servlets_);
        verifyAlerts(() -> extractLog(driver), String.join("\n", getExpectedAlerts()));
    }

    @Test
    @Alerts({"readystatechange_1_0_true", "open-done", "ExceptionThrown"})
    public void onKeyWord_sync_timeout() throws Exception {
        final WebDriver driver = loadPage2(buildHtml(Mode.SYNC_ON_KEYWORD, Execution.TIMEOUT),
                URL_FIRST, servlets_);
        verifyAlerts(() -> extractLog(driver), String.join("\n", getExpectedAlerts()));
    }

    @Test
    @Alerts(DEFAULT = {"readystatechange_1_0_true", "open-done", "loadstart_1_0_false",
                    "send-done", "readystatechange_2_200_true", "readystatechange_3_200_true",
                    "progress_3_200_false", "readystatechange_4_200_true", "load_4_200_false",
                    "loadend_4_200_false"},
            IE = {"readystatechange_1_0_true", "open-done", "readystatechange_1_0_true",
                    "send-done", "loadstart_1_0_false",
                    "readystatechange_2_200_true", "readystatechange_3_200_true",
                    "progress_3_200_false", "readystatechange_4_200_true", "load_4_200_false",
                    "loadend_4_200_false"})
    public void onKeyWord_async() throws Exception {
        final WebDriver driver = loadPage2(buildHtml(Mode.ASYNC_ON_KEYWORD, Execution.ONLY_SEND),
                URL_FIRST, servlets_);
        verifyAlerts(() -> extractLog(driver), String.join("\n", getExpectedAlerts()));
    }

    @Test
    @Alerts(DEFAULT = {"readystatechange_1_0_true", "open-done", "loadstart_1_0_false",
                    "send-done", "readystatechange_4_0_true", "abort_4_0",
                    "loadend_4_0_false", "abort-done"},
            IE = {"readystatechange_1_0_true", "open-done", "readystatechange_1_0_true",
                    "send-done", "readystatechange_4_0_true", "abort_4_0",
                    "loadend_4_0_false", "abort-done"})
    public void onKeyWord_async_abortTriggered() throws Exception {
        final WebDriver driver = loadPage2(buildHtml(Mode.ASYNC_ON_KEYWORD, Execution.SEND_ABORT),
                URL_FIRST, servlets_);
        verifyAlerts(() -> extractLog(driver), String.join("\n", getExpectedAlerts()));
    }

    @Test
    @Alerts(DEFAULT = {"readystatechange_1_0_true", "open-done", "loadstart_1_0_false",
                "send-done", "readystatechange_4_0_true", "error_4_0_false",
                "loadend_4_0_false"},
            IE = {"readystatechange_1_0_true", "open-done", "readystatechange_1_0_true",
                    "send-done", "loadstart_1_0_false", "readystatechange_4_0_true",
                    "error_4_0_false", "loadend_4_0_false"})
    public void onKeyWord_async_networkErrorTriggered() throws Exception {
        final WebDriver driver = loadPage2(buildHtml(Mode.ASYNC_ON_KEYWORD, Execution.NETWORK_ERROR),
                URL_FIRST, servlets_);
        verifyAlerts(() -> extractLog(driver), String.join("\n", getExpectedAlerts()));
    }

    /**
     * Error 500 on the server side still count as a valid requests for {@link XMLHttpRequest}.
     */
    @Test
    @Alerts(DEFAULT = {"readystatechange_1_0_true", "open-done", "loadstart_1_0_false",
                    "send-done", "readystatechange_2_500_true", "readystatechange_3_500_true",
                    "progress_3_500_false", "readystatechange_4_500_true", "load_4_500_false",
                    "loadend_4_500_false"},
            IE = {"readystatechange_1_0_true", "open-done", "readystatechange_1_0_true",
                    "send-done", "loadstart_1_0_false",
                    "readystatechange_2_500_true", "readystatechange_3_500_true",
                    "progress_3_500_false", "readystatechange_4_500_true", "load_4_500_false",
                    "loadend_4_500_false"})
    public void onKeyWord_async_Error500Triggered() throws Exception {
        final WebDriver driver = loadPage2(buildHtml(Mode.ASYNC_ON_KEYWORD, Execution.ERROR_500),
                URL_FIRST, servlets_);
        verifyAlerts(() -> extractLog(driver), String.join("\n", getExpectedAlerts()));
    }

    @Test
    @Alerts(DEFAULT = {"readystatechange_1_0_true", "open-done", "loadstart_1_0_false",
                    "send-done", "readystatechange_4_0_true", "timeout_4_0_false",
                    "loadend_4_0_false"},
            IE = {"readystatechange_1_0_true", "open-done", "readystatechange_1_0_true",
                    "send-done", "loadstart_1_0_false", "readystatechange_2_200_true",
                    "readystatechange_4_0_true", "timeout_4_0_false",
                    "loadend_4_0_false"})
    public void onKeyWord_async_timeout() throws Exception {
        final WebDriver driver = loadPage2(buildHtml(Mode.ASYNC_ON_KEYWORD, Execution.TIMEOUT),
                URL_FIRST, servlets_);
        verifyAlerts(() -> extractLog(driver), String.join("\n", getExpectedAlerts()));
    }

    private static String extractLog(final WebDriver driver) {
        return driver.findElement(By.id("log")).getAttribute("value").trim().replaceAll("\r", "");
    }

    /**
     * Alerts each State that has been triggered in the form of:
     * event.type_(isUndefined?)
     * @param mode
     * @param execution
     * @param statesParam
     * @return
     */
    private String buildHtml(final Mode mode, final Execution execution) {
        final StringBuffer htmlBuilder = new StringBuffer();
        htmlBuilder.append("<html>\n");
        htmlBuilder.append("  <head>\n");
        htmlBuilder.append("    <title>XMLHttpRequest Test</title>\n");
        htmlBuilder.append("    <script>\n");
        htmlBuilder.append("      function test() {\n");
        htmlBuilder.append("        document.getElementById('log').value = '';\n");
        htmlBuilder.append("        xhr = new XMLHttpRequest();\n");
        Arrays.asList(State.values()).forEach(state -> registerEventListener(htmlBuilder, mode, state));

        htmlBuilder.append("        xhr.open('GET', '");
        if (Execution.NETWORK_ERROR.equals(execution)) {
            htmlBuilder.append((URL_FIRST + SUCCESS_URL).replace("http://", "https://"));
        }
        else if (Execution.ERROR_500.equals(execution)) {
            htmlBuilder.append(ERROR_URL);
        }
        else if (Execution.TIMEOUT.equals(execution)) {
            htmlBuilder.append(TIMEOUT_URL);
        }
        else {
            htmlBuilder.append(SUCCESS_URL);
        }
        htmlBuilder.append("', ").append(mode.isAsync()).append(");\n");
        htmlBuilder.append("        logText('open-done');");

        htmlBuilder.append("        try {\n");

        if (Execution.TIMEOUT.equals(execution)) {
            htmlBuilder.append("        xhr.timeout = 10;\n");
        }

        htmlBuilder.append("           xhr.send();\n");
        htmlBuilder.append("           logText('send-done');");
        if (Execution.SEND_ABORT.equals(execution)) {
            htmlBuilder.append("           xhr.abort();\n");
            htmlBuilder.append("           logText('abort-done');");
        }
        htmlBuilder.append("        } catch (e) { logText('ExceptionThrown'); }\n");
        htmlBuilder.append("      }\n");

        htmlBuilder.append("      function alertEventState(event) {\n");
        htmlBuilder.append("        logText(event.type + '_' + xhr.readyState + '_'"
                                        + "+ xhr.status + '_' + (event.loaded === undefined));\n");
        htmlBuilder.append("      }\n");

        htmlBuilder.append("      function alertAbort(event) {\n");
        htmlBuilder.append("        logText(event.type + '_' + xhr.readyState + '_' + xhr.status);\n");
        htmlBuilder.append("      }\n");

        htmlBuilder.append("      function logText(txt) {\n");
        htmlBuilder.append("        document.getElementById('log').value += txt + '\\n';\n");
        htmlBuilder.append("      }\n");
        htmlBuilder.append("    </script>\n");
        htmlBuilder.append("  </head>\n");
        htmlBuilder.append("  <body onload='test()'>\n");
        htmlBuilder.append("    <textarea id='log' cols='80' rows='40'></textarea>\n");
        htmlBuilder.append("  </body>\n");
        htmlBuilder.append("</html>");

        return htmlBuilder.toString();
    }

    void registerEventListener(final StringBuffer buffer, final Mode mode, final State state) {
        String function = "alertEventState";
        if (State.ABORT.equals(state)) {
            function = "alertAbort";
        }

        if (mode.isUseOnKeyword()) {
            buffer.append("        xhr.on").append(state.getEventName_()).append("=").append(function).append(";\n");
        }
        else {
            buffer.append("        xhr.addEventListener('").append(state.getEventName_()).append("', ").append(function)
                    .append(");\n");
        }
    }

    public static class Xml200Servlet extends HttpServlet {

        @Override
        protected void doGet(final HttpServletRequest req, final HttpServletResponse resp)
                throws ServletException, IOException {
            resp.setContentType(MimeType.TEXT_XML);
            resp.setContentLength(RETURN_XML.length());
            resp.setStatus(HttpStatus.SC_OK);
            final ServletOutputStream outputStream = resp.getOutputStream();
            try (Writer writer = new OutputStreamWriter(outputStream)) {
                writer.write(RETURN_XML);
            }

        }
    }

    public static class Xml500Servlet extends HttpServlet {

        @Override
        protected void doGet(final HttpServletRequest req, final HttpServletResponse resp)
                throws ServletException, IOException {
            resp.setContentType(MimeType.TEXT_XML);
            resp.setContentLength(RETURN_XML.length());
            resp.setStatus(HttpStatus.SC_INTERNAL_SERVER_ERROR);
            final ServletOutputStream outputStream = resp.getOutputStream();
            try (Writer writer = new OutputStreamWriter(outputStream)) {
                writer.write(RETURN_XML);
            }
        }
    }

    public static class XmlTimeoutServlet extends HttpServlet {

        @Override
        protected void doGet(final HttpServletRequest req, final HttpServletResponse resp)
                throws ServletException, IOException {
            resp.setContentType(MimeType.TEXT_XML);
            resp.setContentLength(RETURN_XML.length());
            resp.setStatus(HttpStatus.SC_OK);
            final ServletOutputStream outputStream = resp.getOutputStream();
            try (Writer writer = new OutputStreamWriter(outputStream)) {
                writer.flush();
                Thread.sleep(500);
                writer.write(RETURN_XML);
            }
            catch (final Exception e) {
                // ignored.
            }
        }
    }

}
