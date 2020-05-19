package com.lzhpo.ypnettywebsocket.zk;

import lombok.extern.slf4j.Slf4j;
import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * Zookeeper客户端操作API
 * <p>
 * TODO：负载均衡法
 *
 * @author lzhpo
 */
@Component
@Slf4j
public class ZkApi {

    @Autowired
    private ZooKeeper zkClient;

    /**
     * 判断指定节点是否存在
     *
     * @param path
     * @param needWatch 指定是否复用zookeeper中默认的Watcher
     * @return
     */
    public Stat exists(String path, boolean needWatch) {
        try {
            return zkClient.exists(path, needWatch);
        } catch (Exception e) {
            log.error("【断指定节点是否存在异常】{},{}", path, e);
            return null;
        }
    }

    /**
     * 检测结点是否存在 并设置监听事件
     * 三种监听类型： 创建，删除，更新
     *
     * @param path
     * @param watcher 传入指定的监听类
     * @return
     */
    public Stat exists(String path, Watcher watcher) {
        try {
            return zkClient.exists(path, watcher);
        } catch (Exception e) {
            log.error("【断指定节点是否存在异常】{},{}", path, e);
            return null;
        }
    }

    /**
     * 创建持久化节点
     *
     * @param path
     * @param data
     */
    public boolean createNode(String path, String data) {
        try {
            zkClient.create(path, data.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            return true;
        } catch (Exception e) {
            log.error("【创建持久化节点异常[createNode]】{},{},{}", path, data, e);
            return false;
        }
    }

    /**
     * 创建一个临时节点，当客户端断开连接之后就直接删除
     *
     * @param path
     * @param data
     * @return
     */
    public boolean createTemporaryNode(String path, String data) {
        try {
            zkClient.create(path, data.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            return true;
        } catch (Exception e) {
            log.error("【创建临时节点异常[createTemporaryNode]】{},{},{}", path, data, e);
            return false;
        }
    }

    /**
     * 创建临时节点，当客户端断开连接的时候就将其删除，其名称将附加一个单调递增的数字。
     *
     * @param path
     * @param data
     * @return
     */
    public boolean createTemporaryNumNode(String path, String data) {
        try {
            zkClient.create(path, data.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
            return true;
        } catch (Exception e) {
            log.error("【创建临时节点异常[createTemporaryNumNode]】{},{},{}", path, data, e);
            return false;
        }
    }

    /**
     * 修改持久化节点
     *
     * @param path
     * @param data
     */
    public boolean updateNode(String path, String data) {
        try {
            //zk的数据版本是从0开始计数的。如果客户端传入的是-1，则表示zk服务器需要基于最新的数据进行更新。如果对zk的数据节点的更新操作没有原子性要求则可以使用-1.
            //version参数指定要更新的数据的版本, 如果version和真实的版本不同, 更新操作将失败. 指定version为-1则忽略版本检查
            zkClient.setData(path, data.getBytes(), -1);
            return true;
        } catch (Exception e) {
            log.error("【修改持久化节点异常】{},{},{}", path, data, e);
            return false;
        }
    }

    /**
     * 删除持久化节点
     *
     * @param path
     */
    public boolean deleteNode(String path) {
        try {
            //version参数指定要更新的数据的版本, 如果version和真实的版本不同, 更新操作将失败. 指定version为-1则忽略版本检查
            zkClient.delete(path, -1);
            return true;
        } catch (Exception e) {
            log.error("【删除持久化节点异常】{},{}", path, e);
            return false;
        }
    }

    /**
     * 获取当前节点的子节点(不包含孙子节点)
     *
     * @param path 父节点path
     */
    public List<String> getChildren(String path) throws KeeperException, InterruptedException {
        List<String> list = zkClient.getChildren(path, false);
        return list;
    }

    /**
     * 获取指定节点的值
     *
     * @param path
     * @return
     */
    public String getData(String path, Watcher watcher) {
        try {
            Stat stat = new Stat();
            byte[] bytes = zkClient.getData(path, watcher, stat);
            return new String(bytes);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 允许ZK中是两级节点的操作，然后进行返回子节点(第二层节点)的数据。
     *
     * @param parentPath 两个节点的父节点
     * @param watcher watcher
     * @return 父节点下的子节点的节点数据
     * @throws KeeperException
     * @throws InterruptedException
     */
    public List<String> getChildrenArrayData(String parentPath, Watcher watcher) throws KeeperException, InterruptedException {
        ArrayList<String> nodeDataList = new ArrayList<>();
        getChildren(parentPath).forEach(children -> {
            // 获取子节点数据
            String childrenData = getData(parentPath + "/" + children, null);
            nodeDataList.add(childrenData);
        });
        return nodeDataList;
    }


    /**
     * 初始化先创建父节点yp_queue
     */
    @PostConstruct
    public void init() {
        if (exists(ZkConstant.YP_QUEUE, true) == null) {
            createNode(ZkConstant.YP_QUEUE, "Yunpeng message push system.");
        } else {
            log.info("已经存在 [{}] 节点目录，不再创建！", ZkConstant.YP_QUEUE);
        }
    }
}