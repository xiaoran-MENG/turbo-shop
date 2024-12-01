package com.myorg;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecr.RepositoryProps;
import software.amazon.awscdk.services.ecr.TagMutability;
import software.constructs.Construct;

public class EcrStack extends Stack {

    private final Repository productRepository;
    private final Repository auditRepository;
    private final Repository invoiceRepository;

    public EcrStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);
        this.productRepository = this.createRepository("ecr-product-repository");
        this.auditRepository = this.createRepository("ecr-audit-repository");
        this.invoiceRepository = this.createRepository("ecr-invoice-repository");
    }

    public Repository productRepository() {
        return productRepository;
    }

    public Repository auditRepository() {
        return auditRepository;
    }

    public Repository invoiceRepository() {
        return invoiceRepository;
    }

    private Repository createRepository(String name) {
        var props = RepositoryProps.builder()
            .repositoryName(name)
            .removalPolicy(RemovalPolicy.DESTROY)
            .imageTagMutability(TagMutability.IMMUTABLE)
            .autoDeleteImages(true)
            .build();
        return new Repository(this, name, props);
    }
}

