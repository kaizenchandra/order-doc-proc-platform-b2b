{{- define "order.name" -}}
{{- printf "%s-order" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "order.selector" -}}
app.kubernetes.io/name: order-service
app.kubernetes.io/instance: {{ .Release.Name | quote }}
{{- end -}}
{{- define "order.labels" -}}
{{ include "order.selector" . }}
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | quote }}
{{- end -}}
{{- define "order.validate" -}}
{{- if eq .Values.app.uploadBucket .Values.app.reportBucket -}}
{{- fail "Upload and report buckets must differ" -}}
{{- end -}}
{{- if eq .Values.app.orderEventsTopic .Values.app.requestsTopic -}}
{{- fail "Order and request topics must differ" -}}
{{- end -}}
{{- $maximum := int .Values.replicaCount -}}
{{- if .Values.autoscaling.enabled -}}
{{- $maximum = int .Values.autoscaling.maxReplicas -}}
{{- if gt (int .Values.autoscaling.minReplicas) $maximum -}}
{{- fail "autoscaling.minReplicas cannot exceed maxReplicas" -}}
{{- end -}}
{{- end -}}
{{- if gt (mul (add (mul 2 $maximum) 1) (int .Values.database.poolSize)) (int .Values.database.connectionBudget) -}}
{{- fail "Database connectionBudget must cover (2 * maximum replicas + 1) * poolSize, including rollout/termination headroom" -}}
{{- end -}}
{{- if and .Values.networkPolicy.enabled (empty .Values.networkPolicy.sqlCidrs) -}}
{{- fail "networkPolicy.sqlCidrs is required when network policy is enabled" -}}
{{- end -}}
{{- if .Values.ingress.enabled -}}
{{- range $key := list "host" "staticIpName" "preSharedCertificate" -}}
{{- if empty (index $.Values.ingress $key) -}}{{- fail (printf "ingress.%s is required" $key) -}}{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
