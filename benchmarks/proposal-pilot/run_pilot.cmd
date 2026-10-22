@echo off
rem Proposal pilot: last 50 KIPs and last 30 FLIPs, with index and audit chain; then a second KIP pass to
rem exercise changed-after-acceptance against the recorded history, and an evidence report.
set JAR=..\aiv-cli-evidence2.jar
java -jar %JAR% proposal scan --template kip --space KAFKA --last 50 --index --audit-log audit\proposals.log --out kip-last50 > scan-kip.txt 2>&1
java -jar %JAR% proposal scan --template flip --space FLINK --last 30 --index --audit-log audit\proposals.log --out flip-last30 > scan-flip.txt 2>&1
java -jar %JAR% audit verify audit\proposals.log > verify.txt 2>&1
java -jar %JAR% report --audit-log audit\proposals.log --out report\proposals.html --export-jsonl report\jsonl > report.txt 2>&1
echo done > done.txt
