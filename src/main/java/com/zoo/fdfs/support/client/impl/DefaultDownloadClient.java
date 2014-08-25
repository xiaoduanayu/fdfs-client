package com.zoo.fdfs.support.client.impl;

import static com.zoo.fdfs.api.Constants.FDFS_GROUP_NAME_MAX_LEN;
import static com.zoo.fdfs.api.Constants.STORAGE_PROTO_CMD_DOWNLOAD_FILE;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zoo.fdfs.api.Connection;
import com.zoo.fdfs.api.DownloadCallback;
import com.zoo.fdfs.api.FdfsClientConfig;
import com.zoo.fdfs.api.FdfsException;
import com.zoo.fdfs.api.StorageConfig;
import com.zoo.fdfs.api.TrackerClient;
import com.zoo.fdfs.common.Asserts;
import com.zoo.fdfs.common.Circle;
import com.zoo.fdfs.common.IOs;
import com.zoo.fdfs.common.Messages;
import com.zoo.fdfs.common.ResponseBody;
import com.zoo.fdfs.common.Strings;
import com.zoo.fdfs.common.WriteByteArrayFragment;
import com.zoo.fdfs.support.SimpleConnectionPool;
import com.zoo.fdfs.support.client.DownloadClient;


/**
 * 
 * @author yankai913@gmail.com
 * @date 2014-8-21
 */
public class DefaultDownloadClient extends AbstractClient implements DownloadClient {

    private static final Logger logger = LoggerFactory.getLogger(DefaultDownloadClient.class);


    public DefaultDownloadClient(FdfsClientConfig fdfsClientConfig, TrackerClient trackerClient) {
        super(fdfsClientConfig, trackerClient);
    }


    @Override
    public byte[] downloadFile(String groupName, String remoteFileName) throws FdfsException {
        return downloadFile(groupName, remoteFileName, 0, 0);
    }


    @Override
    public byte[] downloadFile(String groupName, String remoteFileName, long fileOffset, long downloadBytes) throws FdfsException {
        // check
        checkBeforeDownload(groupName, remoteFileName);

        Connection con = getFetchableConnection(groupName, remoteFileName);

        OutputStream os = null;
        InputStream is = null;

        try {
            os = con.getOutputStream();
            is = con.getInputStream();
            // send
            sendDownloadRequest(os, groupName, remoteFileName, fileOffset, downloadBytes);

            ResponseBody responseBody = Messages.readStorageResponse(is, -1);
            if (responseBody.getErrorNo() != 0) {
                logger.debug("responseBody.getErrorNo() = [{}]", responseBody.getErrorNo());
                return null;
            }

            byte[] body = responseBody.getBody();
            return body;
        } catch (Exception e) {
            throw new FdfsException("downloadFile fail, " + e.getMessage(), e);
        } finally {
            IOs.close(is, os);
            con.close();
        }
    }


    @Override
    public int downloadFile(String groupName, String remoteFileName, String localFileName) throws FdfsException {
        byte[] srcData = downloadFile(groupName, remoteFileName);
        IOs.writeToLocalFile(srcData, localFileName);
        return 0;
    }


    @Override
    public int downloadFile(String groupName, String remoteFileName, String localFileName, long fileOffset, long downloadBytes) throws FdfsException {
        byte[] srcData = downloadFile(groupName, remoteFileName, fileOffset, downloadBytes);
        IOs.writeToLocalFile(srcData, localFileName);
        return 0;
    }


    @Override
    public int downloadFile(String groupName, String remoteFileName, DownloadCallback callback) throws FdfsException {
        int result = downloadFile(groupName, remoteFileName, callback, 0, 0);
        return result;
    }


    @Override
    public int downloadFile(String groupName, String remoteFileName, DownloadCallback callback, long fileOffset, long downloadBytes) throws FdfsException {
        // check
        checkBeforeDownload(groupName, remoteFileName);

        Connection con = getFetchableConnection(groupName, remoteFileName);

        OutputStream os = null;
        InputStream is = null;

        try {
            os = con.getOutputStream();
            is = con.getInputStream();
            // send
            sendDownloadRequest(os, groupName, remoteFileName, fileOffset, downloadBytes);

            ResponseBody responseBody = Messages.readStorageResponse(is, -1);
            if (responseBody.getErrorNo() != 0) {
                logger.debug("responseBody.getErrorNo() = [{}]", responseBody.getErrorNo());
                return responseBody.getErrorNo();
            }

            byte[] body = responseBody.getBody();

            // TODO Hard Code，固定buf大小。
            byte[] buff = new byte[2 * 1024];
            // 待读取的剩余的长度。
            int remainLength = body.length;
            // 每次读取的长度。
            int readedlength = 0;
            // 作比较，取每次读取的最小length。
            int minLength = remainLength > buff.length ? buff.length : remainLength;
            int result = 0;
            while (remainLength > 0) {
                // 这里是做检查，待读取的长度和实际的读取的长度作比较。
                if ((readedlength = is.read(buff, 0, minLength)) < 0) {
                    throw new FdfsException("invalid recv package size ");
                }
                if ((result = callback.recv(body.length, buff, readedlength)) != 0) {
                    byte errno = (byte) result;
                    assert errno == 0;
                    return result;
                }

                remainLength = remainLength - readedlength;
            }
            return 0;
        } catch (Exception e) {
            throw new FdfsException("downloadFile fail, " + e.getMessage(), e);
        } finally {
            IOs.close(is, os);
            con.close();
        }
    }


    private void checkBeforeDownload(String groupName, String remoteFileName) {
        Asserts.assertStringBlank(groupName, "groupName is blank");
        Asserts.assertStringBlank(remoteFileName, "remoteFileName is blank");
        // log
        logger.debug("groupName=[{}]", groupName);
        logger.debug("remoteFileName=[{}]", remoteFileName);
    }


    private Connection getFetchableConnection(String groupName, String remoteFileName) throws FdfsException {
        logger.debug("open connection...");
        // 拿StorageIP
        Set<StorageConfig> storageConfigSet = getTrackerClient().getFetchStorageSet(groupName, remoteFileName);

        Asserts.assertCollectionBlank(storageConfigSet, "storageConfigSet is empty");

        Circle<StorageConfig> addressCircle = new Circle<StorageConfig>(storageConfigSet);

        final SimpleConnectionPool fetchConnectionPool = new SimpleConnectionPool(getFdfsClientConfig().getFetchPoolSize());

        for (int i = 0; i <= fetchConnectionPool.getElementLength(); i++) {
            InetSocketAddress inetSocketAddress = addressCircle.readNext().getInetSocketAddress();
            try {
                fetchConnectionPool.put(super.newConnection(inetSocketAddress));
            } catch (Exception e) {//跳过异常，后面检查。
                logger.error("newConnection fail, inetSocketAddress=[{}]", inetSocketAddress, e);
            }
        }

        // 真实初始化的个数和配置的个数不一致。
        if (fetchConnectionPool.isEnough()) {
            throw new FdfsException("init fetchConnectionPool fail...");
        }
        // 赋值
        super.setConnectionPool(fetchConnectionPool);

        return fetchConnectionPool.get();
    }


    private void sendDownloadRequest(OutputStream os, String groupName, String remoteFileName, long fileOffset, long downloadBytes) throws IOException {

        byte[] groupNameByteArr = Strings.getBytes(groupName, getFdfsClientConfig().getCharset());
        byte[] remoteFileNameByteArr = Strings.getBytes(remoteFileName, getFdfsClientConfig().getCharset());

        byte errorNo = 0;
        int headerLength = 10;
        int fileOffsetByteArrLength = 8;// long型是8个字节。
        int downloadBytesByteArrLength = 8;
        int groupNameByteArrLength = FDFS_GROUP_NAME_MAX_LEN;
        int remoteFileNameByteArrLength = remoteFileNameByteArr.length;

        int bodyLength = fileOffsetByteArrLength + downloadBytesByteArrLength + groupNameByteArrLength + remoteFileNameByteArrLength;

        int requestTotalLength = headerLength + bodyLength;
        WriteByteArrayFragment request = new WriteByteArrayFragment(requestTotalLength);
        // fill header
        {
            request.writeLong(bodyLength);
            request.writeByte(STORAGE_PROTO_CMD_DOWNLOAD_FILE);
            request.writeByte(errorNo);
        }
        // fill body
        {
            request.writeLong(fileOffset);
            request.writeLong(downloadBytes);
            request.writeSubBytes(groupNameByteArr, FDFS_GROUP_NAME_MAX_LEN);
            request.writeBytes(remoteFileNameByteArr);
        }
        os.write(request.getData());
    }
}
