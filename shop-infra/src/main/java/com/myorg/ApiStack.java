package com.myorg;

import java.util.Arrays;
import java.util.HashMap;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigateway.AccessLogFormat;
import software.amazon.awscdk.services.apigateway.ConnectionType;
import software.amazon.awscdk.services.apigateway.Integration;
import software.amazon.awscdk.services.apigateway.IntegrationOptions;
import software.amazon.awscdk.services.apigateway.IntegrationProps;
import software.amazon.awscdk.services.apigateway.IntegrationType;
import software.amazon.awscdk.services.apigateway.JsonSchema;
import software.amazon.awscdk.services.apigateway.JsonSchemaType;
import software.amazon.awscdk.services.apigateway.JsonWithStandardFieldProps;
import software.amazon.awscdk.services.apigateway.LogGroupLogDestination;
import software.amazon.awscdk.services.apigateway.MethodLoggingLevel;
import software.amazon.awscdk.services.apigateway.MethodOptions;
import software.amazon.awscdk.services.apigateway.Model;
import software.amazon.awscdk.services.apigateway.ModelProps;
import software.amazon.awscdk.services.apigateway.RequestValidator;
import software.amazon.awscdk.services.apigateway.RequestValidatorProps;
import software.amazon.awscdk.services.apigateway.Resource;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.apigateway.RestApiProps;
import software.amazon.awscdk.services.apigateway.StageOptions;
import software.amazon.awscdk.services.apigateway.VpcLink;
import software.amazon.awscdk.services.elasticloadbalancingv2.NetworkLoadBalancer;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogGroupProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

record ApiStackProps (
    NetworkLoadBalancer networkLoadBalancer,
    VpcLink vpcLink
) {}

public class ApiStack extends Stack {
    
    public ApiStack(
        final Construct scope, 
        final String id, 
        final StackProps props,
        final ApiStackProps apiStackProps
    ) {
        super(scope, id, props);

        var logGroupProps = LogGroupProps.builder()
            .logGroupName("shop-api-gateway-log-group")
            .removalPolicy(RemovalPolicy.DESTROY)
            .retention(RetentionDays.ONE_MONTH)
            .build();

        var logGroup = new LogGroup(this, "shop-api-gateway-log-group", logGroupProps);

        var logDestination = new LogGroupLogDestination(logGroup);

        var jsonProps = JsonWithStandardFieldProps.builder()
            .caller(true)
            .httpMethod(true)
            .ip(true)
            .protocol(true)
            .requestTime(true)
            .responseLength(true)
            .resourcePath(true)
            .status(true)
            .user(true) // Checks the law if user info can be generated in the logs
            .build();

        var logFormat = AccessLogFormat.jsonWithStandardFields(jsonProps);

        var deployOptions = StageOptions.builder()
            .loggingLevel(MethodLoggingLevel.INFO)
            .accessLogDestination(logDestination)
            .accessLogFormat(logFormat)
            .build();

        var api = new RestApi(this, "RestApi", RestApiProps.builder()
            .restApiName("shop-rest-api")
            .cloudWatchRole(true) // Enables generation of logs to CloudWatch
            .deployOptions(deployOptions)
            .build());
        
        var products = this.createProductsResource(api, apiStackProps);
        this.createProductEventsResource(api, apiStackProps, products);
        this.createInvoiceResource(api, apiStackProps);
    }

    private String dns(ApiStackProps props) {
        return props.networkLoadBalancer().getLoadBalancerDnsName();
    }

    private void createInvoiceResource(RestApi api, ApiStackProps props) {
        var resource = api.getRoot().addResource("invoices"); // /invoices
        var integrationParams = new HashMap<String, String>() {{
            put("integration.request.header.requestId", "context.requestId");
        }};
        var methodParams = new HashMap<String, Boolean>() {{
            put("method.request.header.requestId", false);
        }};
        var intergrationOptions = IntegrationOptions.builder()
            .vpcLink(props.vpcLink())
            .connectionType(ConnectionType.VPC_LINK)
            .requestParameters(integrationParams)
            .build();
        var uri = "http://" + dns(props) + ":9095/api/invoices";
        var integrationProps = IntegrationProps.builder()
            .type(IntegrationType.HTTP_PROXY)
            .integrationHttpMethod("POST")
            .uri(uri)
            .options(intergrationOptions)
            .build();
        var integration = new Integration(integrationProps);
        var validatorProps = RequestValidatorProps.builder()
            .restApi(api)
            .requestValidatorName("api-gateway-invoices-validator")
            .validateRequestBody(true)
            .build();
        var validator = new RequestValidator(this, "api-gateway-invoices-validator", validatorProps);
        var methodOptions = MethodOptions.builder()
            .requestValidator(validator)
            .requestParameters(methodParams)
            .build();
        resource.addMethod("POST", integration, methodOptions);

        // GET - /invoices/transactions/{fileTransactionId}
        this.createTransactionsResource(api, props, resource); 
        
        // GET - /invoices/email=
        this.createEmailResource(api, props, resource, integrationParams);
    }
        
    private void createEmailResource(RestApi api, ApiStackProps props, Resource parent, HashMap<String, String> parentIntegrationParams) {
        var methodParams = new HashMap<String, Boolean>() {{
            put("method.request.header.requestId", false);
            put("method.request.header.querystring.email", true); // Required
        }};
        var intergrationOptions = IntegrationOptions.builder()
            .vpcLink(props.vpcLink())
            .connectionType(ConnectionType.VPC_LINK)
            .requestParameters(parentIntegrationParams)
            .build();
        var uri = "http://" + dns(props) + ":9095/api/invoices";
        var integrationProps = IntegrationProps.builder()
            .type(IntegrationType.HTTP_PROXY)
            .integrationHttpMethod("GET")
            .uri(uri)
            .options(intergrationOptions)
            .build();
        var integration = new Integration(integrationProps);
        var validatorProps = RequestValidatorProps.builder()
            .restApi(api)
            .requestValidatorName("api-gateway-email-validator")
            .validateRequestBody(true)
            .build();
        var validator = new RequestValidator(this, "api-gateway-email-validator", validatorProps);
        var methodOptions = MethodOptions.builder()
            .requestValidator(validator)
            .requestParameters(methodParams)
            .build();
        parent.addMethod("GET", integration, methodOptions);
    }
        
    private void createTransactionsResource(RestApi api, ApiStackProps props, Resource parent) {
        var integrationParams = new HashMap<String, String>() {{
            put("integration.request.header.requestId", "context.requestId");
            put("integration.request.path.fileTransactionId", "method.request.path.fileTransactionId");
        }};
        var methodParams = new HashMap<String, Boolean>() {{
            put("method.request.header.fileTransactionId", true); // Required
            put("method.request.header.requestId", false);
        }};
        var resource = parent.addResource("transactions").addResource("{fileTransactionId}");
        var intergrationOptions = IntegrationOptions.builder()
            .vpcLink(props.vpcLink())
            .connectionType(ConnectionType.VPC_LINK)
            .requestParameters(integrationParams)
            .build();
        var uri = "http://" + dns(props) + ":9095/api/invoices/transactions/{fileTransactionId}";
        var integrationProps = IntegrationProps.builder()
            .type(IntegrationType.HTTP_PROXY)
            .integrationHttpMethod("GET")
            .uri(uri)
            .options(intergrationOptions)
            .build();
        var integration = new Integration(integrationProps);
        var validatorProps = RequestValidatorProps.builder()
            .restApi(api)
            .requestValidatorName("api-gateway-transactions-validator")
            .validateRequestBody(true)
            .build();
        var validator = new RequestValidator(this, "api-gateway-transactions-validator", validatorProps);
        var methodOptions = MethodOptions.builder()
            .requestValidator(validator)
            .requestParameters(methodParams)
            .build();
        resource.addMethod("GET", integration,  methodOptions);
    }

    private void createProductEventsResource(RestApi api, ApiStackProps props, Resource products) {
        var events = products.addResource("events"); // products/events

        var integrationParams = new HashMap<String, String>() {{
            put("integration.request.header.requestId", "context.requestId");
        }};

        var methodParams = new HashMap<String, Boolean>() {{
            put("method.request.header.requestId", false);
            put("method.request.querystring.eventType", false);
            put("method.request.querystring.take", false);
            put("method.request.querystring.from", false);
            put("method.request.querystring.to", false);
            put("method.request.querystring.startedAtExclusive", false);
        }};

        var validatorProps = RequestValidatorProps.builder()
            .restApi(api)
            .requestValidatorName("api-gateway-product-events-validator")
            .validateRequestBody(true)
            .build();
        var validator = new RequestValidator(this, "api-gateway-product-events-validator", validatorProps);
        var intergrationOptions = IntegrationOptions.builder()
            .vpcLink(props.vpcLink())
            .connectionType(ConnectionType.VPC_LINK)
            .requestParameters(integrationParams)
            .build();
        var networkDns = props.networkLoadBalancer().getLoadBalancerDnsName();
        var integrationProps = IntegrationProps.builder()
            .type(IntegrationType.HTTP_PROXY)
            .integrationHttpMethod("GET")
            .uri("http://" + networkDns + ":9090/api/products/events")
            .options(intergrationOptions) // use VPC Link
            .build();
        var integration = new Integration(integrationProps);
        var methodOptions = MethodOptions.builder()
            .requestValidator(validator)
            .requestParameters(methodParams)
            .build();
        events.addMethod("GET", integration, methodOptions);
    }

    private Resource createProductsResource(RestApi api, ApiStackProps props) {
        var requestIntegrationParams = new HashMap<String, String>() {{
            put("integration.request.header.requestId", "context.requestId");
        }};

        var requestMethodParams = new HashMap<String, Boolean>() {{
            put("method.request.header.requestId", false);
            put("method.request.querystring.code", false); // ?code= not required
        }};

        var products = api.getRoot().addResource("products"); // /products

        // GET /products?code=


        // GET /products
        products.addMethod("GET", new Integration(
            IntegrationProps.builder()
                .type(IntegrationType.HTTP_PROXY)
                .integrationHttpMethod("GET")
                .uri("http://" + props.networkLoadBalancer().getLoadBalancerDnsName() + ":8080/api/products")
                .options(IntegrationOptions.builder()
                    .vpcLink(props.vpcLink())
                    .connectionType(ConnectionType.VPC_LINK)
                    .requestParameters(requestIntegrationParams)
                    .build()) // use VPC Link
                .build()), MethodOptions.builder()
                    .requestParameters(requestMethodParams)
                    .build());

        var validator = new RequestValidator(this, "api-gateway-product-request-validator", 
            RequestValidatorProps.builder()
                .restApi(api)
                .requestValidatorName("api-gateway-product-request-validator")
                .validateRequestBody(true)
                .build());

        var schemaProps = new HashMap<String, JsonSchema>() {{
            put("name", JsonSchema.builder().type(JsonSchemaType.STRING).minLength(5).maxLength(50).build());
            put("code", JsonSchema.builder().type(JsonSchemaType.STRING).minLength(5).maxLength(15).build());
            put("model", JsonSchema.builder().type(JsonSchemaType.STRING).minLength(5).maxLength(50).build());
            put("price", JsonSchema.builder().type(JsonSchemaType.NUMBER).minimum(10.0).maximum(5000.0).build());
        }};

        var schema = new Model(this, "ProductSchema",
            ModelProps.builder()
                .modelName("ProductSchema")
                .restApi(api)
                .contentType("application/json")
                .schema(JsonSchema.builder()
                    .type(JsonSchemaType.OBJECT)
                    .properties(schemaProps)
                    .required(Arrays.asList("name", "code"))
                    .build())
                .build());

        var schemas = new HashMap<String, Model>() {{
            put("application/json", schema);
        }};

        // POST /products
        products.addMethod("POST", new Integration(
            IntegrationProps.builder()
                .type(IntegrationType.HTTP_PROXY)
                .integrationHttpMethod("POST")
                .uri("http://" + props.networkLoadBalancer().getLoadBalancerDnsName() + ":8080/api/products")
                .options(
                    IntegrationOptions.builder()
                        .vpcLink(props.vpcLink())
                        .connectionType(ConnectionType.VPC_LINK)
                        .requestParameters(requestIntegrationParams)
                        .build()) // use VPC Link
                .build()), 
                    MethodOptions.builder()
                        .requestParameters(requestMethodParams)
                        .requestValidator(validator)
                        .requestModels(schemas)
                        .build());

        // PUT /products/{id}
        var requestByIdIntegrationParams = new HashMap<String, String>() {{
            // AWS Pattern
            // path/header
            put("integration.request.path.id", "method.request.path.id"); // Value is where to forward the id to infra
            put("integration.request.header.requestId", "context.requestId");
        }};

        var requestByIdMethodParams = new HashMap<String, Boolean>() {{
            put("method.request.path.id", true); // id is required
            put("method.request.header.requestId", false);
        }};

        var productById = products.addResource("{id}");

        productById.addMethod("PUT", new Integration(
            IntegrationProps.builder()
                .type(IntegrationType.HTTP_PROXY)
                .integrationHttpMethod("PUT")
                .uri("http://" + props.networkLoadBalancer().getLoadBalancerDnsName() + ":8080/api/products/{id}")
                .options(IntegrationOptions.builder()
                    .vpcLink(props.vpcLink())
                    .connectionType(ConnectionType.VPC_LINK)
                    .requestParameters(requestByIdIntegrationParams)
                    .build()) // use VPC Link
                .build()), MethodOptions.builder()
                    .requestParameters(requestByIdMethodParams)
                    .requestValidator(validator)
                    .requestModels(schemas)
                    .build());

        // GET /products/{id}
        productById.addMethod("GET", new Integration(
            IntegrationProps.builder()
                .type(IntegrationType.HTTP_PROXY)
                .integrationHttpMethod("GET")
                .uri("http://" + props.networkLoadBalancer().getLoadBalancerDnsName() + ":8080/api/products/{id}")
                .options(IntegrationOptions.builder()
                    .vpcLink(props.vpcLink())
                    .connectionType(ConnectionType.VPC_LINK)
                    .requestParameters(requestByIdIntegrationParams)
                    .build()) // use VPC Link
                .build()), MethodOptions.builder()
                    .requestParameters(requestByIdMethodParams)
                    .build());

        // DELETE /products/{id}
        productById.addMethod("DELETE", new Integration(
            IntegrationProps.builder()
                .type(IntegrationType.HTTP_PROXY)
                .integrationHttpMethod("DELETE")
                .uri("http://" + props.networkLoadBalancer().getLoadBalancerDnsName() + ":8080/api/products/{id}")
                .options(IntegrationOptions.builder()
                    .vpcLink(props.vpcLink())
                    .connectionType(ConnectionType.VPC_LINK)
                    .requestParameters(requestByIdIntegrationParams)
                    .build()) // use VPC Link
                .build()), MethodOptions.builder()
                    .requestParameters(requestByIdMethodParams)
                    .build());

        return products;
    }
}
