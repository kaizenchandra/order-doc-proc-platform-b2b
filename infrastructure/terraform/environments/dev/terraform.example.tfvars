# Dedicated existing project with billing; never reuse a project across environments.
project_id = "replace-dev-project"
region     = "asia-south1"
# Stage 1 creates only the foundation. Populate images and secrets before Stage 2.
enable_cloud_run  = false
database_prepared = false
# images = {
#   document = "asia-south1-docker.pkg.dev/PROJECT/orders-dev/document-service@sha256:DIGEST"
#   notification = "asia-south1-docker.pkg.dev/PROJECT/orders-dev/notification-service@sha256:DIGEST"
# }
# notification_password_version = "1"
# api_hostname = "orders.example.com"
# dns_managed_zone = "existing-zone"

# notification_channels = ["projects/PROJECT/notificationChannels/CHANNEL_ID"]
