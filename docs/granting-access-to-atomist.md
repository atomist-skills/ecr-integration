They will need

-   our atomist account id is xxxxx
-   a shared "external" id, known to both of us, that is associated with the
    role that they create

Third party account (slimslenderslacks) account-id: 689740959397

-   Policy AmazonEC2ContainerRegistryReadOnly gives us the permissions we need
-   create role named `atomist-ecr-integration` for atomist account using
    external id `atomist`
-   in our case the arn for this role is
    `arn:aws:iam::689740959397:role/atomist-ecr-integration`

[third-party-roles]:
    https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_common-scenarios_third-party.html
[tutorial]:
    https://docs.aws.amazon.com/IAM/latest/UserGuide/tutorial_cross-account-with-roles.html
[confused-deputy]:
    https://research.nccgroup.com/2019/12/18/demystifying-aws-assumerole-and-stsexternalid/
