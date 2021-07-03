## ListRulesCommand with `:NamePrefix` "atomist"

```
{:$metadata
 {:httpStatusCode 200,
  :requestId "b17da1b1-1102-40f6-8748-9cb909508be6",
  :extendedRequestId nil,
  :cfId nil,
  :attempts 1,
  :totalRetryDelay 0},
 :NextToken nil,
 :Rules
 [{:EventPattern
   "{\"source\":[\"aws.ecr\"],\"detail-type\":[\"ECR Image Action\"],\"detail\":{\"action-type\":[\"PUSH\",\"DELETE\"],\"result\":[\"SUCCESS\",\"FAILURE\
"]}}",
   :ManagedBy nil,
   :EventBusName "default",
   :RoleArn nil,
   :Arn "arn:aws:events:us-east-1:111664719423:rule/atomist",
   :Name "atomist",
   :Description "atomist ecr integration",
   :State "ENABLED",
   :ScheduleExpression nil}]}
```

## DescribeApiDestinationCommand with `:Name` "atomist

```
{:$metadata
 {:httpStatusCode 200,
  :requestId "a6fec8b8-aaba-446c-9cd8-11367330a066",
  :extendedRequestId nil,
  :cfId nil,
  :attempts 1,
  :totalRetryDelay 0},
 :NextToken nil,
 :Targets
 [{:RedshiftDataParameters nil,
   :InputTransformer nil,
   :Id "Idc42136af-987f-46bb-87bc-87783b9c153c",
   :Input nil,
   :DeadLetterConfig nil,
   :InputPath nil,
   :RetryPolicy nil,
   :SqsParameters nil,
   :RunCommandParameters nil,
   :RoleArn
   "arn:aws:iam::111664719423:role/service-role/Amazon_EventBridge_Invoke_Api_Destination_513540493",
   :KinesisParameters nil,
   :Arn
   "arn:aws:events:us-east-1:111664719423:api-destination/atomist/9efd5396-661d-45e4-b08d-24e2d6f4b911",
   :BatchParameters nil,
   :SageMakerPipelineParameters nil,
   :EcsParameters nil,
   :HttpParameters
   {:HeaderParameters {},
    :PathParameterValues [],
    :QueryStringParameters {}}}]}
```

## DescribeConnectionCommand with `:Name` "atomist"

```
{:LastAuthorizedTime #inst "2021-06-15T07:45:22.000-00:00",
 :LastModifiedTime #inst "2021-06-15T07:45:22.000-00:00",
 :ConnectionState "AUTHORIZED",
 :$metadata
 {:httpStatusCode 200,
  :requestId "e5843c5e-f566-4ea1-aee0-dc9b51db8863",
  :extendedRequestId nil,
  :cfId nil,
  :attempts 1,
  :totalRetryDelay 0},
 :AuthorizationType "BASIC",
 :ConnectionArn
 "arn:aws:events:us-east-1:111664719423:connection/atomist/7f4d5c15-92b4-4815-922b-be4ff7e22df5",
 :StateReason nil,
 :CreationTime #inst "2021-05-27T19:37:54.000-00:00",
 :AuthParameters
 {:ApiKeyAuthParameters nil,
  :BasicAuthParameters {:Username "atomist"},
  :InvocationHttpParameters
  {:BodyParameters nil,
   :HeaderParameters nil,
   :QueryStringParameters nil},
  :OAuthParameters nil},
 :Name "atomist",
 :SecretArn
 "arn:aws:secretsmanager:us-east-1:111664719423:secret:events!connection/atomist/f1e4f797-3910-4dc4-8449-d70873624b98-f7etXa",
 :Description nil}
```

## ListTargetsByRuleCommand with `:Rule` "atomist"

```
{:$metadata
 {:httpStatusCode 200,
  :requestId "9c98f906-0700-4324-9246-f25fe2b531d7",
  :extendedRequestId nil,
  :cfId nil,
  :attempts 1,
  :totalRetryDelay 0},
 :NextToken nil,
 :Targets
 [{:RedshiftDataParameters nil,
   :InputTransformer nil,
   :Id "Idc42136af-987f-46bb-87bc-87783b9c153c",
   :Input nil,
   :DeadLetterConfig nil,
   :InputPath nil,
   :RetryPolicy nil,
   :SqsParameters nil,
   :RunCommandParameters nil,
   :RoleArn
   "arn:aws:iam::111664719423:role/service-role/Amazon_EventBridge_Invoke_Api_Destination_513540493",
   :KinesisParameters nil,
   :Arn
   "arn:aws:events:us-east-1:111664719423:api-destination/atomist/9efd5396-661d-45e4-b08d-24e2d6f4b911",
   :BatchParameters nil,
   :SageMakerPipelineParameters nil,
   :EcsParameters nil,
   :HttpParameters
   {:HeaderParameters {},
    :PathParameterValues [],
    :QueryStringParameters {}}}]}
```
