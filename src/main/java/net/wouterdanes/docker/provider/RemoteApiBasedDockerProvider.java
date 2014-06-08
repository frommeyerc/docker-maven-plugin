package net.wouterdanes.docker.provider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import com.google.common.base.Optional;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorOutputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

import net.wouterdanes.docker.provider.model.ContainerStartConfiguration;
import net.wouterdanes.docker.provider.model.ImageBuildConfiguration;
import net.wouterdanes.docker.remoteapi.ContainersService;
import net.wouterdanes.docker.remoteapi.ImagesService;
import net.wouterdanes.docker.remoteapi.MiscService;
import net.wouterdanes.docker.remoteapi.exception.ImageNotFoundException;
import net.wouterdanes.docker.remoteapi.model.ContainerCreateRequest;
import net.wouterdanes.docker.remoteapi.model.ContainerStartRequest;
import net.wouterdanes.docker.remoteapi.util.DockerHostFromEnvironmentSupplier;
import net.wouterdanes.docker.remoteapi.util.DockerHostFromPropertySupplier;
import net.wouterdanes.docker.remoteapi.util.DockerPortFromEnvironmentSupplier;
import net.wouterdanes.docker.remoteapi.util.DockerPortFromPropertySupplier;

public abstract class RemoteApiBasedDockerProvider implements DockerProvider {

    private final String host;
    private final int port;

    private final ContainersService containersService;
    private final ImagesService imagesService;
    private final MiscService miscService;

    public static final int DEFAULT_DOCKER_PORT = 4243;
    public static final String DEFAULT_DOCKER_HOST = "127.0.0.1";
    public static final String DOCKER_HOST_SYSTEM_ENV = "DOCKER_HOST";
    public static final String DOCKER_HOST_PROPERTY = "docker.host";
    public static final String DOCKER_PORT_PROPERTY = "docker.port";

    public static final String TCP_PROTOCOL = "tcp";


    public RemoteApiBasedDockerProvider() {
        this(getDockerHostFromEnvironment(), getDockerPortFromEnvironment());
    }


    @Override
    public void stopContainer(final String containerId) {
        getContainersService().killContainer(containerId);
    }

    @Override
    public void deleteContainer(final String containerId) {
        getContainersService().deleteContainer(containerId);
    }

    @Override
    public String buildImage(final ImageBuildConfiguration image) {
        byte[] bytes = getTgzArchiveForFiles(image);
        return miscService.buildImage(bytes, Optional.fromNullable(image.getNameAndTag()));
    }

    private static byte[] getTgzArchiveForFiles(final ImageBuildConfiguration image) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (
                CompressorOutputStream gzipStream = new CompressorStreamFactory().createCompressorOutputStream("gz", baos);
                ArchiveOutputStream tar = new ArchiveStreamFactory().createArchiveOutputStream("tar", gzipStream)
        ) {
            for (File file : image.getFiles()) {
                ArchiveEntry entry = tar.createArchiveEntry(file, file.getName());
                tar.putArchiveEntry(entry);
                byte[] contents = Files.readAllBytes(Paths.get(file.getAbsolutePath()));
                tar.write(contents);
                tar.closeArchiveEntry();
            }
            tar.flush();
            gzipStream.flush();
        } catch (CompressorException | ArchiveException | IOException e) {
            throw new IllegalStateException("Unable to create output archive", e);
        }
        return baos.toByteArray();
    }

    @Override
    public void removeImage(final String imageId) {
        getImagesService().deleteImage(imageId);
    }

    @Override
    public String toString() {
        return getClass().getName() + "{" +
                "host='" + host + '\'' +
                ", port=" + port +
                '}';
    }

    protected RemoteApiBasedDockerProvider(final String host, final int port) {
        this.host = host;
        this.port = port;
        String dockerApiRoot = String.format("http://%s:%s", host, port);
        containersService = new ContainersService(dockerApiRoot);
        imagesService = new ImagesService(dockerApiRoot);
        miscService = new MiscService(dockerApiRoot);
    }

    protected String startContainer(ContainerStartConfiguration configuration, ContainerStartRequest startRequest) {
        String imageId = configuration.getImage();
        ContainerCreateRequest createRequest = new ContainerCreateRequest()
                .fromImage(imageId);

        String containerId;
        try {
            containerId = containersService.createContainer(createRequest);
        } catch (ImageNotFoundException e) {
            imagesService.pullImage(imageId);
            containerId = containersService.createContainer(createRequest);
        }

        containersService.startContainer(containerId, startRequest);

        return containerId;
    }

    protected ContainersService getContainersService() {
        return containersService;
    }

    protected ImagesService getImagesService() {
        return imagesService;
    }

    protected String getHost() {
        return host;
    }

    protected int getPort() {
        return port;
    }

    private static Integer getDockerPortFromEnvironment() {
        return DockerPortFromPropertySupplier.INSTANCE.get()
                .or(DockerPortFromEnvironmentSupplier.INSTANCE.get())
                .or(DEFAULT_DOCKER_PORT);
    }

    private static String getDockerHostFromEnvironment() {
        return DockerHostFromPropertySupplier.INSTANCE.get()
                .or(DockerHostFromEnvironmentSupplier.INSTANCE.get())
                .or(DEFAULT_DOCKER_HOST);
    }

}
