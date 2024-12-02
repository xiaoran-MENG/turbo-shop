package com.myorg;

import java.util.HashMap;
import java.util.List;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public class ShopInfraApp {
    public static void main(final String[] args) {
        var app = new App();

        var env = Environment.builder()
            .account("851725352330")
            .region("us-west-2")
            .build();
        var infraTags = new HashMap<String, String>() {{
            put("team", "UM");
            put("cost", "ShopInfra");
        }};

        // ECR
        EcrStack ecrStack = new EcrStack(app, "Ecr", StackProps.builder()
            .env(env)
            .tags(infraTags)
            .build());

        // VPC
        var vpcStack = new VpcStack(app, "Vpc", StackProps.builder()
            .env(env)
            .tags(infraTags)
            .build());

        // Cluster
        var clusterStack = new ClusterStack(app, "Cluster", StackProps.builder()
            .env(env)
            .tags(infraTags)
            .build(), new ClusterStackProps(vpcStack.getVpc()));
        clusterStack.addDependency(vpcStack); // Deployed after VPC Stack

        // LB
        var lbStack = new LbStack(app, "Lb", StackProps.builder()
            .env(env)
            .tags(infraTags)
            .build(), new LbStackProps(vpcStack.getVpc()));
        lbStack.addDependency(vpcStack);

        // Product
        var productServiceTags = new HashMap<String, String>() {{
            put("team", "um");
            put("cost", "product");
        }};

        var productStack = new ProductStack(app, "Product", 
            StackProps.builder()
                .env(env)
                .tags(productServiceTags)
                .build(), 
            new ProductStackProps(
                    vpcStack.getVpc(), 
                    clusterStack.getCluster(),
                    lbStack.getNetworkLoadBalancer(),
                    lbStack.getApplicationLoadBalancer(),
                    ecrStack.productRepository()));

        // CDK auto-deploys the dependency Stacks
        List.of(vpcStack, clusterStack, lbStack, ecrStack)
            .forEach(productStack::addDependency);

        // Audit
        var auditTags = new HashMap<String, String>() {{
            put("team", "rrc");
            put("cost", "audit");
        }};

        var auditStack = new AuditStack(app, "Audit", 
            StackProps.builder()
                .env(env)
                .tags(auditTags)
                .build(), 
            new AuditStackProps(
                vpcStack.getVpc(), 
                clusterStack.getCluster(),
                lbStack.getNetworkLoadBalancer(),
                lbStack.getApplicationLoadBalancer(),
                ecrStack.auditRepository(),
                productStack.getSnsTopic()));

        // CDK auto-deploys the dependency Stacks
        List.of(
            vpcStack, 
            clusterStack, 
            lbStack, 
            ecrStack, 
            productStack)
        .forEach(auditStack::addDependency);

        // Invoice
        var invoiceTags = new HashMap<String, String>() {{
            put("team", "uw");
            put("cost", "invoice");
        }};

        var invoiceStack = new InvoiceStack(app, "Invoice", 
            StackProps.builder()
                .env(env)
                .tags(invoiceTags)
                .build(), 
            new InvoiceStackProps(
                vpcStack.getVpc(), 
                clusterStack.getCluster(),
                lbStack.getNetworkLoadBalancer(),
                lbStack.getApplicationLoadBalancer(),
                ecrStack.invoiceRepository()));

        // CDK auto-deploys the dependency Stacks
        List.of(
            vpcStack, 
            clusterStack, 
            lbStack, 
            ecrStack, 
            productStack)
        .forEach(invoiceStack::addDependency);

        var apiStack = new ApiStack(app, "Gateway", StackProps.builder()
            .env(env)
            .tags(infraTags)
            .build(), new ApiStackProps(
                lbStack.getNetworkLoadBalancer(),
                lbStack.getVpcLink()));
        apiStack.addDependency(lbStack);

        app.synth();
    }
}

