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

    public EcrStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        this.productRepository = new Repository(this, "ecr-product-repository",
            RepositoryProps.builder()
                .repositoryName("ecr-product-repository")
                .removalPolicy(RemovalPolicy.DESTROY)
                .imageTagMutability(TagMutability.IMMUTABLE)
                .autoDeleteImages(true)
                .build());

        this.auditRepository = new Repository(this, "ecr-audit-repository",
        RepositoryProps.builder()
            .repositoryName("ecr-audit-repository")
            .removalPolicy(RemovalPolicy.DESTROY)
            .imageTagMutability(TagMutability.IMMUTABLE)
            .autoDeleteImages(true)
            .build());
    }

    public Repository productRepository() {
        return productRepository;
    }

    public Repository auditRepository() {
        return auditRepository;
    }
}

