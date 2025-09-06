package ai.kullai.exporter;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.BurpExtension; // correct package
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import burp.api.montoya.core.ByteArray;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.Base64;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Burp Suite extension (Montoya API) that exports Proxy/Logger HTTP history
 * filtered by the provided hostname, to either:
 *  - Burp XML (.xml)
 *  - HAR 1.2 (.har / .json)
 *
 * Includes full raw request/response.
 */
public class HttpHistoryExporter implements BurpExtension {

    private MontoyaApi api;

    // UI widgets
    private JTextField hostField;
    private JComboBox<String> formatCombo;
    private JCheckBox includeResponses;
    private JButton exportButton;
    private JLabel statusLabel;

    // Formats
    private static final String FORMAT_BURP_XML = "Burp XML (.xml)";
    private static final String FORMAT_HAR = "HAR 1.2 (.har/.json)";

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("HTTP History Exporter");

        SwingUtilities.invokeLater(() -> {
            JPanel panel = buildPanel();
            api.userInterface().registerSuiteTab("HTTP Exporter", panel);
        });
    }

    private JPanel buildPanel() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridy = 0;

        // In-scope host
        c.gridx = 0; form.add(new JLabel("In-scope host (exact match):"), c);
        hostField = new JTextField();
        hostField.setToolTipText("e.g., dev.manuf.app");
        c.gridx = 1; c.weightx = 1.0; form.add(hostField, c);

        // Format
        c.gridy++; c.gridx = 0; c.weightx = 0;
        form.add(new JLabel("Export format:"), c);
        formatCombo = new JComboBox<>(new String[]{FORMAT_BURP_XML, FORMAT_HAR});
        c.gridx = 1; c.weightx = 1.0; form.add(formatCombo, c);

        // Include responses
        c.gridy++; c.gridx = 0; c.gridwidth = 2;
        includeResponses = new JCheckBox("Include responses", true);
        form.add(includeResponses, c);

        // Export button
        c.gridy++; c.gridx = 0; c.gridwidth = 2;
        exportButton = new JButton("Export…");
        exportButton.addActionListener(this::onExport);
        form.add(exportButton, c);

        // Status
        c.gridy++; c.gridx = 0; c.gridwidth = 2;
        statusLabel = new JLabel("Ready.");
        form.add(statusLabel, c);

        root.add(form, BorderLayout.NORTH);
        return root;
    }

    private void onExport(ActionEvent evt) {
        String host = hostField.getText().trim();
        if (host.isEmpty()) {
            JOptionPane.showMessageDialog(null, "Please enter an in-scope host (e.g., dev.manuf.app).",
                    "Missing host", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String fmt = Objects.toString(formatCombo.getSelectedItem(), FORMAT_BURP_XML);
        boolean doXml = fmt.equals(FORMAT_BURP_XML);

        JFileChooser chooser = new JFileChooser();
        if (doXml) {
            chooser.setFileFilter(new FileNameExtensionFilter("Burp XML (*.xml)", "xml"));
        } else {
            chooser.setFileFilter(new FileNameExtensionFilter("HAR / JSON (*.har, *.json)", "har", "json"));
        }
        chooser.setDialogTitle("Choose export file");
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File out = chooser.getSelectedFile();
        if (doXml && !out.getName().toLowerCase(Locale.ROOT).endsWith(".xml")) {
            out = new File(out.getParentFile(), out.getName() + ".xml");
        } else if (!doXml && !(out.getName().toLowerCase(Locale.ROOT).endsWith(".har")
                || out.getName().toLowerCase(Locale.ROOT).endsWith(".json"))) {
            out = new File(out.getParentFile(), out.getName() + ".har");
        }

        final File finalOut = out;
        setBusy(true, "Exporting…");
        new Thread(() -> {
            try {
                int count = doXml
                        ? exportBurpXml(finalOut, host, includeResponses.isSelected())
                        : exportHar(finalOut, host, includeResponses.isSelected());

                setBusy(false, "Exported " + count + " item(s) → " + finalOut.getAbsolutePath());
            } catch (Exception ex) {
                setBusy(false, "Export failed: " + ex.getMessage());
                api.logging().logToError("Export failed: " + ex);
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(null, "Export failed:\n" + ex,
                                "Error", JOptionPane.ERROR_MESSAGE));
            }
        }, "export-thread").start();
    }

    private void setBusy(boolean busy, String msg) {
        SwingUtilities.invokeLater(() -> {
            exportButton.setEnabled(!busy);
            statusLabel.setText(msg);
        });
    }

    // ----------------------- Data access helpers -----------------------

    private static byte[] toBytes(ByteArray b) {
        return (b == null) ? new byte[0] : b.getBytes();
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String headerValue(List<HttpHeader> headers, String name) {
        for (HttpHeader h : headers) {
            if (h.name().equalsIgnoreCase(name)) return h.value();
        }
        return null;
    }

    private static String mimeTypeFromHeaders(List<HttpHeader> headers) {
        String ct = headerValue(headers, "Content-Type");
        return (ct == null) ? "HTML" : ct;
    }

    private static String statusTextFromResponse(HttpResponse res) {
        int s = res.statusCode();
        return switch (s) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 500 -> "Internal Server Error";
            default -> "";
        };
    }

    private static String httpVersionFromRequest(HttpRequest req) {
        return "1.1";
    }

    // ----------------------- Export: Burp XML -----------------------

    private int exportBurpXml(File out, String hostFilter, boolean includeResponses) throws Exception {
        List<ProxyHttpRequestResponse> items = api.proxy().history();

        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(out), StandardCharsets.UTF_8))) {

            w.write("""
<?xml version="1.0"?>
<!DOCTYPE items [
<!ELEMENT items (item*)>
<!ATTLIST items burpVersion CDATA "">
<!ATTLIST items exportTime CDATA "">
<!ELEMENT item (time, url, host, port, protocol, method, path, extension, request, status, responselength, mimetype, response, comment)>
<!ELEMENT time (#PCDATA)>
<!ELEMENT url (#PCDATA)>
<!ELEMENT host (#PCDATA)>
<!ATTLIST host ip CDATA "">
<!ELEMENT port (#PCDATA)>
<!ELEMENT protocol (#PCDATA)>
<!ELEMENT method (#PCDATA)>
<!ELEMENT path (#PCDATA)>
<!ELEMENT extension (#PCDATA)>
<!ELEMENT request (#PCDATA)>
<!ATTLIST request base64 (true|false) "false">
<!ELEMENT status (#PCDATA)>
<!ELEMENT responselength (#PCDATA)>
<!ELEMENT mimetype (#PCDATA)>
<!ELEMENT response (#PCDATA)>
<!ATTLIST response base64 (true|false) "false">
<!ELEMENT comment (#PCDATA)>
]>
""");
            String now = new SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.ENGLISH).format(new Date());
            w.write("<items burpVersion=\"HTTP Exporter\" exportTime=\"" + xmlEscape(now) + "\">\n");

            int written = 0;

            for (ProxyHttpRequestResponse x : items) {
                HttpRequest req = x.finalRequest();
                if (req == null) continue;

                String host = req.httpService().host();
                if (host == null || !host.equalsIgnoreCase(hostFilter)) {
                    continue;
                }

                String protocol = req.httpService().secure() ? "https" : "http";
                int port = req.httpService().port();
                String method = req.method();
                String path = req.path();
                if (path == null || path.isEmpty()) path = "/";

                String url = req.url();
                String ext = "";

                byte[] rawReq = toBytes(req.toByteArray());
                String reqB64 = base64(rawReq);

                boolean hasResp = x.hasResponse() && includeResponses;
                HttpResponse res = hasResp ? x.response() : null;
                int status = hasResp ? res.statusCode() : 0;
                byte[] rawRes = hasResp ? toBytes(res.toByteArray()) : new byte[0];
                String resB64 = hasResp ? base64(rawRes) : "";
                String mime = hasResp ? mimeTypeFromHeaders(res.headers()) : "HTML";

                String time = (x.time() != null) ? x.time().toString() : now;

                w.write("  <item>\n");
                w.write("    <time>" + xmlEscape(time) + "</time>\n");
                w.write("    <url><![CDATA[" + url + "]]></url>\n");
                w.write("    <host ip=\"\">" + xmlEscape(host) + "</host>\n");
                w.write("    <port>" + port + "</port>\n");
                w.write("    <protocol>" + protocol + "</protocol>\n");
                w.write("    <method><![CDATA[" + method + "]]></method>\n");
                w.write("    <path><![CDATA[" + path + "]]></path>\n");
                w.write("    <extension>" + xmlEscape(ext) + "</extension>\n");
                w.write("    <request base64=\"true\"><![CDATA[" + reqB64 + "]]></request>\n");
                w.write("    <status>" + status + "</status>\n");
                w.write("    <responselength>" + rawRes.length + "</responselength>\n");
                w.write("    <mimetype>" + xmlEscape(mime) + "</mimetype>\n");
                w.write("    <response base64=\"" + (hasResp ? "true" : "false") + "\"><![CDATA[" + resB64 + "]]></response>\n");
                w.write("    <comment></comment>\n");
                w.write("  </item>\n");

                written++;
            }

            w.write("</items>\n");
            return written;
        }
    }

    private static String xmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ----------------------- Export: HAR 1.2 -----------------------

    private int exportHar(File out, String hostFilter, boolean includeResponses) throws Exception {
        List<ProxyHttpRequestResponse> items = api.proxy().history();
        ObjectMapper om = new ObjectMapper();

        ObjectNode log = om.createObjectNode();
        log.putObject("creator").put("name", "HTTP History Exporter").put("version", "1.0.0");
        log.put("version", "1.2");
        var entries = om.createArrayNode();
        log.set("entries", entries);

        DateTimeFormatter iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

        int written = 0;

        for (ProxyHttpRequestResponse x : items) {
            HttpRequest req = x.finalRequest();
            if (req == null) continue;

            String host = req.httpService().host();
            if (host == null || !host.equalsIgnoreCase(hostFilter)) {
                continue;
            }

            String url = req.url();
            String method = req.method();
            String httpVersion = "HTTP/" + httpVersionFromRequest(req);
            List<HttpHeader> rh = req.headers();

            ObjectNode hReq = om.createObjectNode();
            hReq.put("method", method);
            hReq.put("url", url);
            hReq.put("httpVersion", httpVersion);
            var hdrs = om.createArrayNode();
            for (HttpHeader h : rh) {
                hdrs.add(om.createObjectNode().put("name", h.name()).put("value", h.value()));
            }
            hReq.set("headers", hdrs);

            byte[] rawReq = toBytes(req.toByteArray());
            String reqText = new String(rawReq, StandardCharsets.ISO_8859_1);
            ObjectNode postData = om.createObjectNode();
            postData.put("mimeType", Optional.ofNullable(headerValue(rh, "Content-Type")).orElse(""));
            postData.put("text", reqText);
            hReq.set("postData", postData);

            ObjectNode hRes = om.createObjectNode();
            if (x.hasResponse() && includeResponses) {
                HttpResponse res = x.response();
                List<HttpHeader> sh = res.headers();
                hRes.put("status", res.statusCode());
                hRes.put("statusText", statusTextFromResponse(res));
                hRes.put("httpVersion", "HTTP/1.1");
                var shdrs = om.createArrayNode();
                for (HttpHeader h : sh) {
                    shdrs.add(om.createObjectNode().put("name", h.name()).put("value", h.value()));
                }
                hRes.set("headers", shdrs);

                byte[] rawRes = toBytes(res.toByteArray());
                String resText = new String(rawRes, StandardCharsets.ISO_8859_1);
                ObjectNode content = om.createObjectNode();
                content.put("size", rawRes.length);
                content.put("mimeType", Optional.ofNullable(headerValue(sh, "Content-Type")).orElse(""));
                content.put("text", resText);
                hRes.set("content", content);

            } else {
                hRes.put("status", 0);
                hRes.put("statusText", "");
                hRes.put("httpVersion", "HTTP/1.1");
                hRes.set("headers", om.createArrayNode());
                ObjectNode content = om.createObjectNode();
                content.put("size", 0);
                content.put("mimeType", "");
                content.put("text", "");
                hRes.set("content", content);
            }

            // Convert ZonedDateTime -> ISO string with offset for HAR
            ZonedDateTime zdt = (x.time() != null) ? x.time() : ZonedDateTime.now();
            String started = iso.format(zdt);

            ObjectNode entry = om.createObjectNode();
            entry.put("startedDateTime", started);
            entry.put("time", 0);
            entry.set("request", hReq);
            entry.set("response", hRes);
            entry.set("cache", om.createObjectNode());
            entry.set("timings", om.createObjectNode().put("send", 0).put("wait", 0).put("receive", 0));

            entries.add(entry);
            written++;
        }

        ObjectNode root = om.createObjectNode();
        root.set("log", log);

        try (OutputStream os = new FileOutputStream(out)) {
            om.writerWithDefaultPrettyPrinter().writeValue(os, root);
        }
        return written;
    }
}