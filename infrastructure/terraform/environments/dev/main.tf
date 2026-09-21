provider "google" {
  project = var.project_id
  region  = var.region
}
provider "google-beta" {
  project = var.project_id
  region  = var.region
}
module "platform" {
  source                        = "../../modules/platform"
  project_id                    = var.project_id
  region                        = var.region
  environment                   = "dev"
  enable_cloud_run              = var.enable_cloud_run
  images                        = var.images
  notification_password_version = var.notification_password_version
  database_prepared             = var.database_prepared
  api_hostname                  = var.api_hostname
  dns_managed_zone              = var.dns_managed_zone
  notification_channels         = var.notification_channels
}
output "deployment" { value = module.platform.deployment }
output "helm_platform_values" { value = module.platform.helm_platform_values }
