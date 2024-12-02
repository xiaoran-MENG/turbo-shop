package com.myorg;

import java.util.Collections;
import java.util.HashMap;
import java.util.Objects;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.AwsLogDriver;
import software.amazon.awscdk.services.ecs.AwsLogDriverProps;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.FargateService;
import software.amazon.awscdk.services.ecs.FargateServiceProps;
import software.amazon.awscdk.services.ecs.FargateTaskDefinition;
import software.amazon.awscdk.services.ecs.FargateTaskDefinitionProps;
import software.amazon.awscdk.services.ecs.LoadBalancerTargetOptions;
import software.amazon.awscdk.services.ecs.PortMapping;
import software.amazon.awscdk.services.ecs.Protocol;
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

record InvoiceStackProps(
    Vpc vpc,
    Cluster cluster,
    NetworkLoadBalancer networkLoadBalancer,
    ApplicationLoadBalancer applicationLoadBalancer,
    Repository repository
) {}

public class InvoiceStack extends Stack {
    private static final int PORT_INVOICE = 9095;

    public InvoiceStack(
        final Construct scope,
        final String id,
        final StackProps stackProps,
        final InvoiceStackProps invoiceStackProps
    ) {
        super(scope, id, stackProps);
        var env = this.env();
        var blueprint = this.createFargateTaskDefinition();
        this.assignXrayWriteOnlyAccess(blueprint);
        var invoiceLogDriver = this.createInvoiceLogDriver();
        this.createInvoiceContainer(invoiceStackProps, env, blueprint, invoiceLogDriver);
        var xrayLogDriver = this.createXrayLogDriver();
        this.createXrayContainer(blueprint, xrayLogDriver);
        var applicationLoadBalancerListener = this.createApplicationLoadBalancerListener(invoiceStackProps);
        var fargateService = this.createFargateService(invoiceStackProps, blueprint);
        this.grantAccessToRepository(invoiceStackProps, blueprint);
        var cidr = invoiceStackProps.vpc().getVpcCidrBlock();
        this.configureFargateServicePort(fargateService, cidr);
        this.addApplicationLoadBalancerTargetGroup(applicationLoadBalancerListener, fargateService);
        var networkListener = createNetworkLoadBalancerListener(invoiceStackProps);
        this.addNetworkLoadBalancerTargetGroup(fargateService, networkListener);
    }

    private HashMap<String, String> env() {
        return new HashMap<String, String>() {{
            put("SERVER_PORT", "9095");
            put("AWS_REGION", getRegion());
            put("AWS_XRAY_DAEMON_ADDRESS", "0.0.0.0:2000");
            put("AWS_XRAY_CONTEXT_MISSING", "IGNORE_ERROR");
            put("AWS_XRAY_TRACING_NAME", "invoice-tracing");
            put("LOGGING_LEVEL_ROOT", "INFO");
        }};
    }

    private void addNetworkLoadBalancerTargetGroup(FargateService fargateService, NetworkListener networkListener) {
        var options = LoadBalancerTargetOptions.builder()
            .containerName("invoice-container")
            .containerPort(PORT_INVOICE)
            .protocol(Protocol.TCP)
            .build();
        var target = fargateService.loadBalancerTarget(options);
        var targets = Collections.singletonList(target);
        var props = AddNetworkTargetsProps.builder()
            .port(PORT_INVOICE)
            .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP)
            .targetGroupName("invoice-network-targets")
            .targets(targets)
            .build();
        networkListener.addTargets("invoice-network-targets", props);
    }

    private NetworkListener createNetworkLoadBalancerListener(final InvoiceStackProps auditStackProps) {
        var props = BaseNetworkListenerProps.builder()
            .port(PORT_INVOICE)
            .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP)
            .build();
        return auditStackProps.networkLoadBalancer().addListener("invoice-network-listener", props);
    }

    private void addApplicationLoadBalancerTargetGroup(ApplicationListener listener, FargateService fargateService) {
        var healthCheck = HealthCheck.builder()
            .enabled(true)
            .interval(Duration.seconds(30)) // Needs to be longer than timeout
            .timeout(Duration.seconds(25))
            .path("/actuator/health")
            .port("9095")
            .build();
        var addTargetsProps = AddApplicationTargetsProps.builder()
            .targetGroupName("invoice-application-targets")
            .port(PORT_INVOICE)
            .protocol(ApplicationProtocol.HTTP)
            .targets(Collections.singletonList(fargateService))
            .deregistrationDelay(Duration.seconds(30)) // Time to remove an unhealth instance
            .healthCheck(healthCheck)
            .build();
        listener.addTargets("invoice-application-targets", addTargetsProps);
    }

    private void configureFargateServicePort(FargateService fargateService, String cidr) {
        fargateService
            .getConnections()
            .getSecurityGroups()
            .get(0)
            .addIngressRule(Peer.ipv4(cidr), Port.tcp(PORT_INVOICE));
    }

    private void grantAccessToRepository(final InvoiceStackProps props, FargateTaskDefinition blueprint) {
        var role = Objects.requireNonNull(blueprint.getExecutionRole());
        props.repository().grantPull(role);
    }

    private FargateService createFargateService(final InvoiceStackProps invoiceStackProps, FargateTaskDefinition blueprint) {
        var props = FargateServiceProps.builder()
            .serviceName("invoice-fargate-service")
            .cluster(invoiceStackProps.cluster())
            .taskDefinition(blueprint)
            .desiredCount(2)
            .assignPublicIp(true) // When using natgateway(0)
            .build();
        return new FargateService(this, "invoice-fargate-service", props);
    }

    private ApplicationListener createApplicationLoadBalancerListener(final InvoiceStackProps stackProps) {
        var props = ApplicationListenerProps.builder()
            .port(PORT_INVOICE)
            .protocol(ApplicationProtocol.HTTP)
            .loadBalancer(stackProps.applicationLoadBalancer())
            .build();
        return stackProps
            .applicationLoadBalancer()
            .addListener("invoice-application-listener", props);
    }
    
    private void createXrayContainer(FargateTaskDefinition blueprint, AwsLogDriver xrayLogDriver) {
        var image = ContainerImage.fromRegistry("public.ecr.aws/xray/aws-xray-daemon:latest");
        var mapping = PortMapping.builder()
            .containerPort(2000)
            .protocol(Protocol.UDP)
            .build();
        var options = ContainerDefinitionOptions.builder()
            .image(image) // Public AWS image
            .containerName("xray-invoice-container")
            .logging(xrayLogDriver)
            .portMappings(Collections.singletonList(mapping))
            .cpu(128)
            .memoryLimitMiB(128)
            .build();
        blueprint.addContainer("xray-invoice-container", options);
    }

    private AwsLogDriver createXrayLogDriver() {
        var groupProps = LogGroupProps.builder()
            .logGroupName("xrayinvoice")
            .removalPolicy(RemovalPolicy.DESTROY)
            .retention(RetentionDays.ONE_MONTH)
            .build();
        var group = new LogGroup(this, "xrayinvoice", groupProps);
        var driverProps = AwsLogDriverProps.builder()
            .logGroup(group)
            .streamPrefix("xray-invoice")
            .build();
        return new AwsLogDriver(driverProps);
    }

    private AwsLogDriver createInvoiceLogDriver() {
        var groupProps = LogGroupProps.builder()
            .logGroupName("invoice")
            .removalPolicy(RemovalPolicy.DESTROY)
            .retention(RetentionDays.ONE_MONTH)
            .build();
        var group = new LogGroup(this, "invoice", groupProps);
        var driverProps = AwsLogDriverProps.builder()
            .logGroup(group)
            .streamPrefix("invoice")
            .build();
        return new AwsLogDriver(driverProps);
    }

    private void assignXrayWriteOnlyAccess(FargateTaskDefinition blueprint) {
        var policy = ManagedPolicy.fromAwsManagedPolicyName("AWSXrayWriteOnlyAccess");
        blueprint.getTaskRole().addManagedPolicy(policy);
    }

    private void createInvoiceContainer(final InvoiceStackProps props, HashMap<String, String> env,FargateTaskDefinition blueprint, AwsLogDriver logDriver) {
        var image = ContainerImage.fromEcrRepository(props.repository(), "1.0.0");
        var mapping = PortMapping.builder()
            .containerPort(PORT_INVOICE)
            .protocol(Protocol.TCP)
            .build();
        var options = ContainerDefinitionOptions.builder()
            .image(image)
            .containerName("invoice-container")
            .logging(logDriver)
            .portMappings(Collections.singletonList(mapping))
            .environment(env)
            .cpu(384) // Out of the 512 defined in the task definition
            .memoryLimitMiB(896)
            .build();
        blueprint.addContainer("invoice-container", options);
    }

    private FargateTaskDefinition createFargateTaskDefinition() {
        var props = FargateTaskDefinitionProps.builder()
            .family("invoice")
            .cpu(512)
            .memoryLimitMiB(1024)
            .build();
        return new FargateTaskDefinition(this, "fargate-task-definition", props);
    }
}
