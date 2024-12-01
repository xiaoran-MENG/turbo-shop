package com.myorg;

import java.util.Collections;
import java.util.HashMap;
import java.util.Objects;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.applicationautoscaling.EnableScalingProps;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.GlobalSecondaryIndexProps;
import software.amazon.awscdk.services.dynamodb.ProjectionType;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.dynamodb.TableProps;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.AwsLogDriver;
import software.amazon.awscdk.services.ecs.AwsLogDriverProps;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.CpuUtilizationScalingProps;
import software.amazon.awscdk.services.ecs.FargateService;
import software.amazon.awscdk.services.ecs.FargateServiceProps;
import software.amazon.awscdk.services.ecs.FargateTaskDefinition;
import software.amazon.awscdk.services.ecs.FargateTaskDefinitionProps;
import software.amazon.awscdk.services.ecs.LoadBalancerTargetOptions;
import software.amazon.awscdk.services.ecs.PortMapping;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.AddApplicationTargetsProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.AddNetworkTargetsProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationListenerProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationProtocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.BaseNetworkListenerProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck;
import software.amazon.awscdk.services.elasticloadbalancingv2.NetworkLoadBalancer;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogGroupProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.TopicProps;
import software.constructs.Construct;

record ProductStackProps(
    Vpc vpc,
    Cluster cluster,
    NetworkLoadBalancer networkLoadBalancer,
    ApplicationLoadBalancer applicationLoadBalancer,
    Repository repository
) {}

public class ProductStack extends Stack {

    private final Topic snsTopic; // SNS topic for product events

    public Topic getSnsTopic() {
        return snsTopic;
    }

    public ProductStack(
        final Construct scope,
        final String id,
        final StackProps props,
        final ProductStackProps productStackProps) {
            super(scope, id, props);

            // SNS
            // Topic
            this.snsTopic = new Topic(this, "shop-sns-product-events-topic", TopicProps.builder()
                .displayName("shop-sns-product-events-topic")
                .topicName("shop-sns-product-events")
                .build());

            // Email subscription for testing
            // this.eventsTopic.addSubscription(
            //     new EmailSubscription("xrmeng720@gmail.com", 
            //         EmailSubscriptionProps.builder()
            //             .json(true)
            //             .build()));

            // DynamoDb
            var table = new Table(this, "ProductsDb", TableProps.builder()
                .partitionKey(Attribute.builder()
                    .name("id")
                    .type(AttributeType.STRING)
                    .build())
                .tableName("products") // dev/qa/prod
                .removalPolicy(RemovalPolicy.DESTROY)
                .billingMode(BillingMode.PAY_PER_REQUEST) // Or PROVISIONED
                // .readCapacity(1) // PROVISIONED
                // .writeCapacity(1) // PROVISIONED
                .build());

            // GSI
            table.addGlobalSecondaryIndex(GlobalSecondaryIndexProps.builder()
                .indexName("IndexOnCode")
                .partitionKey(Attribute.builder()
                    .name("code")
                    .type(AttributeType.STRING)
                    .build())
                .projectionType(ProjectionType.KEYS_ONLY) // Returns only the projected field | a subset of fields | all fields
                // .readCapacity(1) // PROVISIONED
                // .writeCapacity(1) // PROVISIONED
                .build());

            // PROVISIONED
            // var readScale = table.autoScaleReadCapacity(software.amazon.awscdk.services.dynamodb.EnableScalingProps.builder()
            //     .maxCapacity(4)
            //     .minCapacity(1)
            //     .build());

            // readScale.scaleOnUtilization(UtilizationScalingProps.builder()
            //     .targetUtilizationPercent(10)
            //     .scaleInCooldown(Duration.seconds(20))
            //     .scaleOutCooldown(Duration.seconds(20))
            //     .build());

            // var writeScale = table.autoScaleWriteCapacity(software.amazon.awscdk.services.dynamodb.EnableScalingProps.builder()
            //     .maxCapacity(4)
            //     .minCapacity(1)
            //     .build());

            // writeScale.scaleOnUtilization(UtilizationScalingProps.builder()
            //     .targetUtilizationPercent(10)
            //     .scaleInCooldown(Duration.seconds(20))
            //     .scaleOutCooldown(Duration.seconds(20))
            //     .build());

            
            // var indexReadScale = table.autoScaleGlobalSecondaryIndexReadCapacity("IndexOnCode", software.amazon.awscdk.services.dynamodb.EnableScalingProps.builder()
            //     .maxCapacity(4)
            //     .minCapacity(1)
            //     .build());

            // indexReadScale.scaleOnUtilization(UtilizationScalingProps.builder()
            //     .targetUtilizationPercent(10)
            //     .scaleInCooldown(Duration.seconds(20))
            //     .scaleOutCooldown(Duration.seconds(20))
            //     .build());
            
            // No need to define EC2 instances with AWS Fargate
            var taskDefinition = new FargateTaskDefinition(this, "fargate-task-definition", FargateTaskDefinitionProps.builder()
                .family("product")
                .cpu(512)
                .memoryLimitMiB(1024)
                .build());

            // Access granted by DynamoDB table to the ECS task
            table.grantReadWriteData(taskDefinition.getTaskRole());
            // Publish granted by SNS topic to the ECS task
            this.snsTopic.grantPublish(taskDefinition.getTaskRole());

            // Product CloudWatch
            var productLogDriver = new AwsLogDriver(AwsLogDriverProps.builder()
                .logGroup(new LogGroup(this, "product",
                    LogGroupProps.builder()
                        .logGroupName("product")
                        .removalPolicy(RemovalPolicy.DESTROY)
                        .retention(RetentionDays.ONE_MONTH)
                        .build()))
                .streamPrefix("product")
                .build());

            // Overwrites Spring Boot env
            var env = new HashMap<String, String>() {{
                put("SERVER_PORT", "8080");
                put("AWS_PRODUCT_TABLE_NAME", table.getTableName()); // AWS SDK needs to know the table name so the Spring Boot service knows the target table
                put("AWS_SNS_TOPIC_PRODUCT_EVENTS", snsTopic.getTopicArn()); // AWS SDK needs to know topic AWS resource name (arn) knows the target topic
                put("AWS_REGION", getRegion());
                put("AWS_XRAY_DAEMON_ADDRESS", "0.0.0.0:2000"); // UDP
                put("AWS_XRAY_CONTEXT_MISSING", "IGNORE_ERROR"); // Ignores this error in CloudWatch Logs
                put("AWS_XRAY_TRACING_NAME", "product-tracing");
                put("LOGGING_LEVEL_ROOT", "INFO"); // Binds to and controls the log level in Spring Boot
            }};

            // Product container
            taskDefinition.addContainer("product-container", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromEcrRepository(productStackProps.repository(), "1.8.0"))
                .containerName("product-container")
                .logging(productLogDriver)
                .portMappings(Collections.singletonList(PortMapping.builder()
                    .containerPort(8080)
                    .protocol(Protocol.TCP)
                    .build()))
                .environment(env)
                .cpu(384) // Out of the 512 defined in the task definition
                .memoryLimitMiB(896)
                .build());

            // XRay CloudWatch - product
            var xrayLogDriver = new AwsLogDriver(AwsLogDriverProps.builder()
                .logGroup(new LogGroup(this, "xrayproduct",
                    LogGroupProps.builder()
                        .logGroupName("xrayproduct")
                        .removalPolicy(RemovalPolicy.DESTROY)
                        .retention(RetentionDays.ONE_MONTH)
                        .build()))
                .streamPrefix("xray-product")
                .build());

            // XRray container
            taskDefinition.addContainer("xray-product-container", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry("public.ecr.aws/xray/aws-xray-daemon:latest")) // Public AWS image
                .containerName("xray-product-container")
                .logging(xrayLogDriver)
                .portMappings(Collections.singletonList(
                    PortMapping.builder()
                        .containerPort(2000)
                        .protocol(Protocol.UDP)
                        .build()))
                .cpu(128)
                .memoryLimitMiB(128)
                .build());

            // The AWSXrayWriteOnlyAccess policy grants write-only permissions to the AWS X-Ray service
            // The task role associated with this task definition writes trace data to AWS X-Ray, 
            // allowing the task to send tracing information for monitoring and debugging without reading X-Ray data
            taskDefinition.getTaskRole().addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName("AWSXrayWriteOnlyAccess"));

            // Application listener
            var applicationListener = productStackProps.applicationLoadBalancer().addListener("product-application-listener", ApplicationListenerProps.builder()
                .port(8080)
                .protocol(ApplicationProtocol.HTTP)
                .loadBalancer(productStackProps.applicationLoadBalancer())
                .build());
            
            // Create application instances
            var fargateService = new FargateService(this, "product-fargate-service", FargateServiceProps.builder()
                .serviceName("product-fargate-servic")
                .cluster(productStackProps.cluster())
                .taskDefinition(taskDefinition)
                .desiredCount(2)
                .assignPublicIp(true) // When using natgateway(0)
                .build());

            // Service permission
            productStackProps
                .repository()
                .grantPull(Objects.requireNonNull(taskDefinition.getExecutionRole()));
            
            // Security group rules
            fargateService
                .getConnections()
                .getSecurityGroups()
                .get(0)
                .addIngressRule(
                    Peer.ipv4(productStackProps.vpc().getVpcCidrBlock()), 
                    Port.tcp(8080));

            // Target groups
            applicationListener.addTargets("product-application-targets", AddApplicationTargetsProps.builder()
                .targetGroupName("product-application-targets")
                .port(8080)
                .protocol(ApplicationProtocol.HTTP)
                .targets(Collections.singletonList(fargateService))
                .deregistrationDelay(Duration.seconds(30)) // Time to remove an unhealth instance
                .healthCheck(HealthCheck.builder()
                    .enabled(true)
                    .interval(Duration.seconds(30)) // Needs to be longer than timeout
                    .timeout(Duration.seconds(25))
                    .path("/actuator/health")
                    .port("8080")
                    .build())
                .build());

            // Network listener
            var networkListener = productStackProps.networkLoadBalancer()
                .addListener("product-network-listener", BaseNetworkListenerProps.builder()
                    .port(8080)
                    .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP)
                    .build());

            networkListener.addTargets("product-network-targets", AddNetworkTargetsProps.builder()
                .port(8080)
                .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP)
                .targetGroupName("product-network-targets")
                .targets(Collections.singletonList(
                    fargateService.loadBalancerTarget(LoadBalancerTargetOptions.builder()
                        .containerName("product-container")
                        .containerPort(8080)
                        .protocol(Protocol.TCP)
                        .build())))
                .build());

            // Auto scaling
            this.autoScale(fargateService);
    }

    private void autoScale(FargateService fargateService) {
        var scalingProps = EnableScalingProps.builder()
            .maxCapacity(4)
            .minCapacity(2)
            .build();
        var count = fargateService.autoScaleTaskCount(scalingProps);
        var cpuProps = CpuUtilizationScalingProps.builder()
            .targetUtilizationPercent(10) // 80 in prod
            .scaleInCooldown(Duration.seconds(60)) // Waits 60 seconds to apply
            .scaleOutCooldown(Duration.seconds(60))
            .build();
        count.scaleOnCpuUtilization("product-auto-scaling", cpuProps);
    }
}
