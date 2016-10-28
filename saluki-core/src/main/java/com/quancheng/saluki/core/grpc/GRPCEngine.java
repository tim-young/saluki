package com.quancheng.saluki.core.grpc;

import java.io.File;
import java.io.InputStream;
import java.util.Map;

import javax.net.ssl.SSLException;

import com.quancheng.saluki.core.common.SalukiConstants;
import com.quancheng.saluki.core.common.SalukiURL;
import com.quancheng.saluki.core.grpc.client.GrpcClientContext;
import com.quancheng.saluki.core.grpc.client.GrpcProtocolClient;
import com.quancheng.saluki.core.grpc.exception.RpcFrameworkException;
import com.quancheng.saluki.core.grpc.interceptor.HeaderClientInterceptor;
import com.quancheng.saluki.core.grpc.interceptor.HeaderServerInterceptor;
import com.quancheng.saluki.core.grpc.server.GrpcServerContext;
import com.quancheng.saluki.core.registry.Registry;
import com.quancheng.saluki.core.registry.RegistryProvider;

import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.LoadBalancer;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;

public class GRPCEngine {

    private final SalukiURL   registryUrl;

    private final Registry    registry;

    private final InputStream caRoot = this.getClass().getResourceAsStream(name);

    public GRPCEngine(SalukiURL registryUrl){
        this.registryUrl = registryUrl;
        this.registry = RegistryProvider.asFactory().newRegistry(registryUrl);
    }

    public Object getProxy(SalukiURL refUrl) throws Exception {
        boolean localProcess = refUrl.getParameter(SalukiConstants.GRPC_IN_LOCAL_PROCESS, Boolean.FALSE);
        GrpcProtocolClient.ChannelCall call = new GrpcProtocolClient.ChannelCall() {

            @Override
            public Channel getChannel() {
                Channel channel;
                if (localProcess) {
                    channel = InProcessChannelBuilder.forName(SalukiConstants.GRPC_IN_LOCAL_PROCESS).build();
                } else {
                    channel = NettyChannelBuilder.forTarget(registryUrl.toJavaURI().toString())//
                                                 .nameResolverFactory(new SalukiNameResolverProvider(refUrl))//
                                                 .loadBalancerFactory(buildLoadBalanceFactory())//
                                                 .sslContext(buildClientSslContext())//
                                                 .usePlaintext(false)//
                                                 .build();//
                }
                return ClientInterceptors.intercept(channel, new HeaderClientInterceptor());
            }

        };
        GrpcClientContext context = new GrpcClientContext(refUrl, call);
        return context.getGrpcClient();
    }

    private SslContext buildClientSslContext() {
        try {
            return SslContextBuilder.forClient()//
                                    .sslProvider(SslProvider.OPENSSL)//
                                    .keyManager(new File("cacert.pem"), new File("client.pem"))//
                                    .build();
        } catch (SSLException e) {
            throw new RpcFrameworkException(e);
        }
    }

    private LoadBalancer.Factory buildLoadBalanceFactory() {
        return SalukiRoundRobinLoadBalanceFactory.getInstance();
    }

    public SalukiServer getServer(Map<SalukiURL, Object> providerUrls, int port) throws Exception {
        final NettyServerBuilder remoteServer = NettyServerBuilder.forPort(port)//
                                                                  .useTransportSecurity(new File("cacert.pem"),
                                                                                        new File("server.pem"));
        final InProcessServerBuilder injvmServer = InProcessServerBuilder.forName(SalukiConstants.GRPC_IN_LOCAL_PROCESS);
        for (Map.Entry<SalukiURL, Object> entry : providerUrls.entrySet()) {
            SalukiURL providerUrl = entry.getKey();
            Object protocolImpl = entry.getValue();
            GrpcServerContext context = new GrpcServerContext(providerUrl, protocolImpl);
            ServerServiceDefinition serviceDefinition = ServerInterceptors.intercept(context.getServerDefintion(),
                                                                                     new HeaderServerInterceptor());
            remoteServer.addService(serviceDefinition);
            injvmServer.addService(serviceDefinition);
            String registryPort = System.getProperty(SalukiConstants.REGISTRY_PORT,
                                                     Integer.valueOf(providerUrl.getPort()).toString());
            providerUrl = providerUrl.setPort(Integer.valueOf(registryPort).intValue());
            registry.register(providerUrl);
        }
        return new SalukiServer(injvmServer.build().start(), remoteServer.build().start());
    }

}
