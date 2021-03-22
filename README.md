# `@atomist/ecr-integration`

### OnWebhook (from AWS EventBridge)

-   ingest new Image and it's layers

### Subscription: docker-refresh-tags-schedule.edn

For all head commits on default branches, check whether there's an associated
DockerImage from `hub.docker.com`, and then grab the associated Dockerfile, and
it's `FROM` instruction's repository. Now, ingest the latest tag just in case
there's been a new one that we don't know about.

TODO: is this really needed for private registries?

### Subscription: new-docker-image-from-tag.edn

### Subscription: new-docker-file-without-image.edn

### Subscription: new-docker-image-from-digest.edn

[docker-login]:
    https://medium.com/@mohitshrestha02/how-to-login-to-amazon-ecr-and-store-your-local-docker-images-with-an-example-9aa845c4134c
[ecr-event-bridge]:
    https://docs.aws.amazon.com/AmazonECR/latest/userguide/ecr-eventbridge.html

Created by [Atomist][atomist]. Need Help? [Join our Slack workspace][slack].

[atomist]: https://atomist.com/ "Atomist - How Teams Deliver Software"
[slack]: https://join.atomist.com/ "Atomist Community Slack"
