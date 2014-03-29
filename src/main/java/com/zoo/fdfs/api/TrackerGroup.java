package com.zoo.fdfs.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.slf4j.LoggerFactory;
import com.zoo.fdfs.common.Strings;
import org.slf4j.Logger;


/**
 * 
 * @author yankai913@gmail.com
 * @date 2014-3-18
 */
public class TrackerGroup {

    private static final Logger logger = LoggerFactory.getLogger(TrackerGroup.class);

    private final Set<String> availableTrackerServerAddrSet = new HashSet<String>();

    private final Set<String> unavailableTrackerServerAddrSet = new HashSet<String>();

    private FdfsClientConfigurable fdfsClientConfigurable;


    public TrackerGroup(FdfsClientConfigurable fdfsClientConfigurable) {
        this.fdfsClientConfigurable = fdfsClientConfigurable;
        if (Strings.isBlank(this.fdfsClientConfigurable.getTrackerServerAddr())) {
            throw new IllegalArgumentException("trackerServerAddr is blank");
        }
        String[] trackerServerAddrArr = this.fdfsClientConfigurable.getTrackerServerAddr().split(",");
        for (String addr : trackerServerAddrArr) {
            availableTrackerServerAddrSet.add(addr.trim());
        }
        // check
        checkTrackerAddr();
    }


    /**
     * 启动时检查
     * 
     * @return
     */
    private void checkTrackerAddr() {
        Iterator<String> ite = availableTrackerServerAddrSet.iterator();
        while (ite.hasNext()) {
            Socket socket = null;
            String addr = ite.next();
            try {
                socket = createSocket(addr);
            }
            catch (Exception e) {
                logger.error("address:{} create socket failed!", addr);
                logger.error(e.getMessage(), e);
            }
            finally {
                if (socket != null && socket.isConnected()) {
                    try {
                        socket.close();
                    }
                    catch (IOException e) {
                        logger.error("socket:{} close failed!", socket);
                    }
                }
            }
        }
        throw new IllegalStateException("no available socket");
    }


    private Socket createSocket(String addr) throws Exception {
        String[] addrPair = addr.split(":");
        String host = addrPair[0];
        int port = Integer.parseInt(addrPair[1]);
        InetSocketAddress inetSocketAddress = new InetSocketAddress(host, port);
        Socket socket = new Socket();
        socket.setSoTimeout(fdfsClientConfigurable.getReadTimeout());
        socket.connect(inetSocketAddress, fdfsClientConfigurable.getConnectTimeout());
        return socket;
    }


    public Socket getAvailableSocket() {
        Iterator<String> availableIterator = availableTrackerServerAddrSet.iterator();
        Socket socket = null;
        while (availableIterator.hasNext()) {
            String addr = availableIterator.next();
            try {
                socket = createSocket(addr);
                if (socket.isConnected()) {
                    return socket;
                }
            }
            catch (Exception e) {
                logger.error("address:{} create socket failed!", addr);
                logger.error(e.getMessage(), e);
                availableIterator.remove();
                unavailableTrackerServerAddrSet.add(addr);
            }
        }
        if (socket == null || !socket.isConnected()) {
            logger.error("availableTrackerServerAddrSet has no available tracker server socket");
        }
        Iterator<String> unavailableIterator = unavailableTrackerServerAddrSet.iterator();
        while (unavailableIterator.hasNext()) {
            String addr = unavailableIterator.next();
            try {
                socket = createSocket(addr);
                if (socket.isConnected()) {
                    unavailableIterator.remove();
                    availableTrackerServerAddrSet.add(addr);
                    return socket;
                }
            }
            catch (Exception e) {
                logger.error("address:{} create socket failed!", addr);
                logger.error(e.getMessage(), e);
            }
        }
        if (socket == null || !socket.isConnected()) {
            logger.error("unavailableTrackerServerAddrSet has no available tracker server socket");
        }
        if (availableTrackerServerAddrSet.isEmpty()) {
            logger.error("availableTrackerServerAddrSet is empty");
        }
        return null;
    }


    public Set<String> getAvailableTrackerServerAddrSet() {
        return availableTrackerServerAddrSet;
    }


    public Set<Socket> getGroupSocket() {
        Set<Socket> set = new HashSet<Socket>();
        for (String addr : availableTrackerServerAddrSet) {
            try {
                set.add(createSocket(addr));
            }
            catch (Exception e) {
                logger.error("getGroupSocket,addr:{} create socket fail,");
            }
        }
        if (set.size() > 0) {
            return set;
        }
        Socket socket = getAvailableSocket();
        if (socket != null) {
            set.add(socket);
        }
        return set;
    }
}
