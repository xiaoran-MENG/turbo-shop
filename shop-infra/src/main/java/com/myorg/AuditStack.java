package com.myorg;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.applicationautoscaling.EnableScalingProps;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
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
import software.amazon.awscdk.services.ecs.TaskDefinition;
import software.amazon.awscdk.services.elasticloadbalancingv2.AddApplicationTargetsProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.AddNetworkTargetsProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationListener;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationListenerProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationProtocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.BaseNetworkListenerProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck;
import software.amazon.awscdk.services.elasticloadbalancingv2.NetworkListener;
import software.amazon.awscdk.services.elasticloadbalancingv2.NetworkLoadBalancer;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogGroupProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.sns.StringConditions;
import software.amazon.awscdk.services.sns.SubscriptionFilter;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.subscriptions.SqsSubscriptionProps;
import software.amazon.awscdk.services.sns.subscriptions.SqsSubscription;
import software.amazon.awscdk.services.sqs.DeadLetterQueue;
import software.amazon.awscdk.services.sqs.Queue;
import software.amazon.awscdk.services.sqs.QueueEncryption;
import software.amazon.awscdk.services.sqs.QueueProps;
import software.constructs.Construct;

record AuditStackProps(
    Vpc vpc,
    Cluster cluster,
    NetworkLoadBalancer networkLoadBalancer,
    ApplicationLoadBalancer applicationLoadBalancer,
    Repository repository,
    Topic productSnsTopic
) {}

public class AuditStack extends Stack {

    private static final int PORT_AUDIT = 9090;
    
    public AuditStack(
        final Construct scope,
        final String id,
        final StackProps stackProps,
        final AuditStackProps auditStackProps
    ) {
        super(scope, id, stackProps);
        var eventsTable = this.createEventsTable();
        var deadLetter = this.createSqsDeadLetterQueue();
        var queue = this.createSqsQueue(deadLetter);
        this.subscribeToSnsTopic(auditStackProps.productSnsTopic(), queue, Arrays.asList("PRODUCT_CREATED","PRODUCT_UPDATED","PRODUCT_DELETED"));
        var failureQueue = this.createSqsFailureQueue(deadLetter);
        this.subscribeToSnsTopic(auditStackProps.productSnsTopic(), failureQueue, Arrays.asList("PRODUCT_FAILURE"));
        var env = this.env(queue, failureQueue, eventsTable);
        var blueprint = this.createFargateTaskDefinition();
        this.assignXrayWriteOnlyAccess(blueprint);
        var auditLogDriver = this.createAuditLogDriver();
        this.createAuditContainer(auditStackProps, env, blueprint, auditLogDriver);
        var xrayLogDriver = this.createXrayLogDriver();
        this.createXrayContainer(blueprint, xrayLogDriver);
        var applicationListener = this.createApplicationLoadBalancerListener(auditStackProps);
        var fargateService = this.createFargateService(auditStackProps, blueprint);
        this.grantAccessToRepository(auditStackProps, blueprint);
        this.configureFargateServicePort(fargateService, auditStackProps.vpc().getVpcCidrBlock());
        this.addApplicationLoadBalancerTargetGroup(applicationListener, fargateService);
        var networkListener = createNetworkLoadBalancerListener(auditStackProps);
        this.addNetworkLoadBalancerTargetGroup(fargateService, networkListener);
        this.grantAccessToSqsQueue(queue, blueprint);
        this.grantAccessToSqsQueue(failureQueue, blueprint);
        this.grantAccessToEventsTable(eventsTable, blueprint);
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
        count.scaleOnCpuUtilization("audit-auto-scaling", cpuProps);
    }

    private void grantAccessToEventsTable(Table table, TaskDefinition blueprint) {
        table.grantReadWriteData(blueprint.getTaskRole());
    }

    private Table createEventsTable() {
        // Composite primary key
        var partitionKey = Attribute.builder()
            .name("pk")
            .type(AttributeType.STRING)
            .build();
        var sortKey = Attribute.builder()
            .name("sk")
            .type(AttributeType.STRING)
            .build();
        var props = TableProps.builder()
            .tableName("events")
            .removalPolicy(RemovalPolicy.DESTROY)
            .partitionKey(partitionKey)
            .sortKey(sortKey)
            .timeToLiveAttribute("ttl")
            .billingMode(BillingMode.PAY_PER_REQUEST)
            // .readCapacity(1)
            // .writeCapacity(1)
            .build();
        return new Table(this, "events-table", props);
    }

    private void grantAccessToSqsQueue(Queue subscriber, FargateTaskDefinition blueprint) {
        subscriber.grantConsumeMessages(blueprint.getTaskRole());
    }

    private void subscribeToSnsTopic(final Topic topic, Queue subscriber, List<String> allowList) {
        var policy = this.createFilterPolicy(allowList);
        var props = SqsSubscriptionProps.builder()
            .filterPolicy(policy)
            .build();
        topic.addSubscription(new SqsSubscription(subscriber, props));
    }

    private HashMap<String, SubscriptionFilter> createFilterPolicy(List<String> allowList) {
        var conditions = StringConditions.builder()
            .allowlist(allowList)
            .build();
        return new HashMap<String, SubscriptionFilter>() {{
            put("eventType", SubscriptionFilter.stringFilter(conditions));
        }};
    }

    private Queue createSqsQueue(DeadLetterQueue deadLetter) {
        var props = QueueProps.builder()
            .queueName("product-sqs")
            .enforceSsl(false)
            .encryption(QueueEncryption.UNENCRYPTED)
            .deadLetterQueue(deadLetter)
            .build();
        var queue = new Queue(this, "product-sqs", props);
        return queue;
    }

    private Queue createSqsFailureQueue(DeadLetterQueue deadLetter) {
        var props = QueueProps.builder()
            .queueName("product-failure-sqs")
            .enforceSsl(false)
            .encryption(QueueEncryption.UNENCRYPTED)
            .deadLetterQueue(deadLetter)
            .build();
        return new Queue(this, "product-failure-sqs", props);
    }

    private DeadLetterQueue createSqsDeadLetterQueue() {
        var props = QueueProps.builder()
            .queueName("product-sqs-dead-letter")
            .retentionPeriod(Duration.days(10))
            .enforceSsl(false)
            .encryption(QueueEncryption.UNENCRYPTED)
            .build();
        var queue = new Queue(this, "product-sqs-dead-letter", props);
        return DeadLetterQueue.builder()
            .queue(queue)
            .maxReceiveCount(3) // Move an event that fails 3 times to dead letter queue
            .build();
    }

    private void addNetworkLoadBalancerTargetGroup(FargateService fargateService, NetworkListener networkListener) {
        var options = LoadBalancerTargetOptions.builder()
            .containerName("audit-container")
            .containerPort(PORT_AUDIT)
            .protocol(Protocol.TCP)
            .build();
        var target = fargateService.loadBalancerTarget(options);
        var targets = Collections.singletonList(target);
        var props = AddNetworkTargetsProps.builder()
            .port(PORT_AUDIT)
            .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP)
            .targetGroupName("audit-network-targets")
            .targets(targets)
            .build();
        networkListener.addTargets("audit-network-targets", props);
    }

    private NetworkListener createNetworkLoadBalancerListener(final AuditStackProps auditStackProps) {
        var props = BaseNetworkListenerProps.builder()
            .port(PORT_AUDIT)
            .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP)
            .build();
        return auditStackProps.networkLoadBalancer().addListener("audit-network-listener", props);
    }

    private void addApplicationLoadBalancerTargetGroup(@NotNull ApplicationListener listener, FargateService fargateService) {
        var healthCheck = HealthCheck.builder()
            .enabled(true)
            .interval(Duration.seconds(30)) // Needs to be longer than timeout
            .timeout(Duration.seconds(25))
            .path("/actuator/health")
            .port("9090")
            .build();
        var addTargetsProps = AddApplicationTargetsProps.builder()
            .targetGroupName("audit-application-targets")
            .port(PORT_AUDIT)
            .protocol(ApplicationProtocol.HTTP)
            .targets(Collections.singletonList(fargateService))
            .deregistrationDelay(Duration.seconds(30)) // Time to remove an unhealth instance
            .healthCheck(healthCheck)
            .build();
        listener.addTargets("audit-application-targets", addTargetsProps);
    }

    private void configureFargateServicePort(FargateService fargateService, String cidr) {
        fargateService
            .getConnections()
            .getSecurityGroups()
            .get(0)
            .addIngressRule(Peer.ipv4(cidr), Port.tcp(PORT_AUDIT));
    }

    private void grantAccessToRepository(final AuditStackProps auditStackProps, FargateTaskDefinition blueprint) {
        var role = Objects.requireNonNull(blueprint.getExecutionRole());
        auditStackProps.repository().grantPull(role);
    }

    private FargateService createFargateService(final AuditStackProps auditStackProps, FargateTaskDefinition blueprint) {
        var props = FargateServiceProps.builder()
            .serviceName("audit-fargate-service")
            .cluster(auditStackProps.cluster())
            .taskDefinition(blueprint)
            .desiredCount(2)
            .assignPublicIp(true) // When using natgateway(0)
            .build();
        return new FargateService(this, "audit-fargate-service", props);
    }

    private ApplicationListener createApplicationLoadBalancerListener(final AuditStackProps auditStackProps) {
        var props = ApplicationListenerProps.builder()
            .port(PORT_AUDIT)
            .protocol(ApplicationProtocol.HTTP)
            .loadBalancer(auditStackProps.applicationLoadBalancer())
            .build();
        return auditStackProps
            .applicationLoadBalancer()
            .addListener("audit-application-listener", props);
    }

    private void createXrayContainer(FargateTaskDefinition blueprint, AwsLogDriver xrayLogDriver) {
        var image = ContainerImage.fromRegistry("public.ecr.aws/xray/aws-xray-daemon:latest");
        var mapping = PortMapping.builder()
            .containerPort(2000)
            .protocol(Protocol.UDP)
            .build();
        var options = ContainerDefinitionOptions.builder()
            .image(image) // Public AWS image
            .containerName("xray-audit-container")
            .logging(xrayLogDriver)
            .portMappings(Collections.singletonList(mapping))
            .cpu(128)
            .memoryLimitMiB(128)
            .build();
        blueprint.addContainer("xray-audit-container", options);
    }

    private AwsLogDriver createXrayLogDriver() {
        var groupProps = LogGroupProps.builder()
            .logGroupName("xrayaudit")
            .removalPolicy(RemovalPolicy.DESTROY)
            .retention(RetentionDays.ONE_MONTH)
            .build();
        var group = new LogGroup(this, "xrayaudit", groupProps);
        var driverProps = AwsLogDriverProps.builder()
            .logGroup(group)
            .streamPrefix("xray-audit")
            .build();
        return new AwsLogDriver(driverProps);
    }

    private void createAuditContainer(
        final AuditStackProps auditStackProps, 
        HashMap<String, String> env,
        FargateTaskDefinition blueprint, 
        AwsLogDriver auditLogDriver
    ) {
        var image = ContainerImage.fromEcrRepository(auditStackProps.repository(), "1.6.0");
        var mapping = PortMapping.builder()
            .containerPort(PORT_AUDIT)
            .protocol(Protocol.TCP)
            .build();
        var options = ContainerDefinitionOptions.builder()
            .image(image)
            .containerName("audit-container")
            .logging(auditLogDriver)
            .portMappings(Collections.singletonList(mapping))
            .environment(env)
            .cpu(384) // Out of the 512 defined in the task definition
            .memoryLimitMiB(896)
            .build();
        blueprint.addContainer("audit-container", options);
    }

    private HashMap<String, String> env(Queue queue, Queue failureQueue, Table table) {
        return new HashMap<String, String>() {{
            put("SERVER_PORT", "9090");
            put("AWS_REGION", getRegion());
            put("AWS_XRAY_DAEMON_ADDRESS", "0.0.0.0:2000"); // UDP
            put("AWS_XRAY_CONTEXT_MISSING", "IGNORE_ERROR"); // Ignores this error in CloudWatch Logs
            put("AWS_XRAY_TRACING_NAME", "audit-tracing");
            put("LOGGING_LEVEL_ROOT", "INFO"); // Binds to and controls the log level in Spring Boot
            put("AWS_SQS_PRODUCT_URL", queue.getQueueUrl());
            put("AWS_SQS_PRODUCT_FAILURE_URL", failureQueue.getQueueUrl());
            put("AWS_EVENTS_TABLE", table.getTableName());
        }};
    }

    private AwsLogDriver createAuditLogDriver() {
        var groupProps = LogGroupProps.builder()
            .logGroupName("audit")
            .removalPolicy(RemovalPolicy.DESTROY)
            .retention(RetentionDays.ONE_MONTH)
            .build();
        var group = new LogGroup(this, "audit", groupProps);
        var driverProps = AwsLogDriverProps.builder()
            .logGroup(group)
            .streamPrefix("audit")
            .build();
        return new AwsLogDriver(driverProps);
    }

    private void assignXrayWriteOnlyAccess(FargateTaskDefinition blueprint) {
        blueprint.getTaskRole().addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName("AWSXrayWriteOnlyAccess"));
    }

    private FargateTaskDefinition createFargateTaskDefinition() {
        var props = FargateTaskDefinitionProps.builder()
            .family("audit")
            .cpu(512)
            .memoryLimitMiB(1024)
            .build();
        return new FargateTaskDefinition(this, "fargate-task-definition", props);
    }
}
