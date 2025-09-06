HTTP History Exporter (Burp Suite Extension)

A simple Burp Suite extension built on the Montoya API that lets you:
	•	Export full HTTP history (requests + responses) for a single in-scope host.
	•	Choose between Burp XML or HAR 1.2 format.
	•	Filter by exact host (e.g. dev.manuf.app).
	•	Include or exclude responses.
	•	Save the results to a file for further automation or analysis.

⸻

✨ Features
	•	GUI Tab in Burp:
Provides a new HTTP Exporter tab with options to select host, format, and whether to include responses.
	•	Supported Formats:
	•	Burp XML (.xml) — compatible with Burp’s native export schema.
	•	HAR 1.2 (.har, .json) — for use in browsers, curl converters, or API replay tools.
	•	Scope Filtering:
Only exports requests matching the exact host you provide (e.g. dev.manuf.app).
	•	Portable JAR:
Packaged as a shaded JAR (includes Jackson), so no external dependencies are needed in Burp.

⸻

📦 Build

1.	Clone this repo:
 
```
git clone https://github.com/kullaisec/burp-http-exporter.git
cd burp-http-exporter
```

2.	Build the shaded JAR with Maven:
 
```
mvn -q -DskipTests clean package
```


3.	The output will be:
```
target/burp-exporter-1.0.0-shaded.jar
```


⸻

🚀 Install in Burp
	1.	Open Burp → Extensions → Installed → Add.
	2.	Choose Extension Type: Java.
	3.	Select the JAR:

```
target/burp-exporter-1.0.0-shaded.jar
```

4.	A new tab HTTP Exporter will appear.

⸻

🛠️ Usage
	1.	Go to the HTTP Exporter tab.
	2.	Enter the exact host you want to filter by (e.g. dev.manuf.app).
	3.	Select the export format:
	•	Burp XML (.xml)
	•	HAR 1.2 (.har/.json)
	4.	(Optional) Uncheck Include responses if you only want requests.
	5.	Click Export… → choose file location.

You’ll see a status message showing how many items were exported.

⸻

🔍 Example Exports
	•	Burp XML:
Compatible with Burp’s standard export schema — can be parsed by automation tools.
	•	HAR 1.2:
Replayable in Chrome DevTools, Postman, or other HAR parsers.

⸻

🧩 Requirements
	•	Burp Suite Professional or Community 2023.9+ (Montoya API support).
	•	Java 17+ (recommended Java 17 for compatibility).
	•	Maven 3.9+ to build.

⸻

🤝 Contributing

Pull requests and issues are welcome!
Ideas:
	•	Support wildcard hosts (*.domain.com)
	•	Export multiple hosts at once
	•	More output formats (CSV, JSONL)
