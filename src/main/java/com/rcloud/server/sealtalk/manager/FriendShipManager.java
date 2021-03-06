package com.rcloud.server.sealtalk.manager;

import com.google.common.collect.ImmutableList;
import com.rcloud.server.sealtalk.configuration.ProfileConfig;
import com.rcloud.server.sealtalk.constant.Constants;
import com.rcloud.server.sealtalk.constant.ErrorCode;
import com.rcloud.server.sealtalk.domain.BlackLists;
import com.rcloud.server.sealtalk.domain.DataVersions;
import com.rcloud.server.sealtalk.domain.Friendships;
import com.rcloud.server.sealtalk.domain.Users;
import com.rcloud.server.sealtalk.exception.ServiceException;
import com.rcloud.server.sealtalk.exception.ServiceRuntimeException;
import com.rcloud.server.sealtalk.model.dto.ContractInfoDTO;
import com.rcloud.server.sealtalk.model.dto.FriendDTO;
import com.rcloud.server.sealtalk.model.dto.InviteDTO;
import com.rcloud.server.sealtalk.rongcloud.RongCloudClient;
import com.rcloud.server.sealtalk.service.BlackListsService;
import com.rcloud.server.sealtalk.service.DataVersionsService;
import com.rcloud.server.sealtalk.service.FriendshipsService;
import com.rcloud.server.sealtalk.service.UsersService;
import com.rcloud.server.sealtalk.util.CacheUtil;
import com.rcloud.server.sealtalk.util.JacksonUtil;
import com.rcloud.server.sealtalk.util.MiscUtils;
import com.rcloud.server.sealtalk.util.N3d;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import tk.mybatis.mapper.entity.Example;

import javax.annotation.Resource;
import java.util.*;

/**
 * @Author: xiuwei.nie
 * @Author: Jianlu.Yu
 * @Date: 2020/7/6
 * @Description:
 * @Copyright (c) 2020, rongcloud.cn All Rights Reserved
 */
@Service
@Slf4j
public class FriendShipManager extends BaseManager {


    public static final String NONE = "None";
    public static final String ADDED = "Added";

    @Resource
    private RongCloudClient rongCloudClient;

    @Resource
    private UsersService usersService;

    @Resource
    private FriendshipsService friendshipsService;

    @Resource
    private BlackListsService blackListsService;

    @Resource
    private DataVersionsService dataVersionsService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * ??????????????????
     *
     * @param currentUserId
     * @param friendId
     * @param message
     * @return
     * @throws ServiceException
     */
    public InviteDTO invite(Integer currentUserId, Integer friendId, String message)
            throws ServiceException {
        log.info("invite user. currentUserId:[{}] friendId:[{}]", currentUserId, friendId);
        InviteDTO inviteResponse = null;

        Users users = usersService.getByPrimaryKey(friendId);
        Integer friVerify = users.getFriVerify();

        if (Users.FRI_VERIFY_NEED.equals(friVerify)) {
            // ??????????????????
            inviteResponse = addVerifyFriend(currentUserId, friendId, message);
        } else {
            // ??????????????????????????????
            inviteResponse = addNoNeedVerifyFriend(currentUserId, friendId, message);
        }
        return inviteResponse;
    }

    private InviteDTO addVerifyFriend(Integer currentUserId, Integer friendId, String message)
            throws ServiceException {
        String action = NONE;
        String resultMessage = "";

        //??????????????????????????????
        Friendships friendshipsCF = friendshipsService.getOneByUserIdAndFriendId(currentUserId, friendId);
        //????????????????????????
        Friendships friendshipsFC = friendshipsService.getOneByUserIdAndFriendId(friendId, currentUserId);

        BlackLists b = new BlackLists();
        b.setUserId(friendId);
        b.setFriendId(currentUserId);
        BlackLists blackLists = blackListsService.getOne(b);

        if (blackLists != null && BlackLists.STATUS_VALID.equals(blackLists.getStatus()) && Friendships.FRIENDSHIP_PULLEDBLACK.equals(friendshipsCF.getStatus())) {
            //????????????????????????????????????????????????Do nothing.
            resultMessage = "Do nothing.";
            return new InviteDTO(action, resultMessage);
        }

        action = ADDED;
        resultMessage = "Friend added.";
        long timestamp = System.currentTimeMillis();

        if (friendshipsCF != null && friendshipsFC != null) {
            if (Friendships.FRIENDSHIP_AGREED.equals(friendshipsCF.getStatus()) && Friendships.FRIENDSHIP_AGREED.equals(friendshipsFC.getStatus())) {
                //??????????????????????????????????????????????????????
                String errorMsg = "User " + N3d.encode(friendId) + " is already your friend.";
                throw new ServiceException(ErrorCode.ALREADY_YOUR_FRIEND, errorMsg);
            }

            Calendar now_1 = Calendar.getInstance();
            Calendar now_3 = Calendar.getInstance();
            if (Constants.ENV_DEV.equals(sealtalkConfig.getConfigEnv())) {
                now_1.add(Calendar.SECOND, -1);
                now_3.add(Calendar.SECOND, -3);
            } else {
                now_1.add(Calendar.DATE, -1);
                now_3.add(Calendar.DATE, -3);
            }

            Calendar cfUpdateAtCal = Calendar.getInstance();
            cfUpdateAtCal.setTime(friendshipsCF.getUpdatedAt());

            //?????????????????????
            int cfStatus;
            int fcStatus;

            if (Friendships.FRIENDSHIP_REQUESTING.equals(friendshipsFC.getStatus())) {
                //???????????????????????????????????????????????? ??????????????????
                //???????????????????????????????????????-?????????????????????????????????
                cfStatus = Friendships.FRIENDSHIP_AGREED;
                fcStatus = Friendships.FRIENDSHIP_AGREED;
                message = friendshipsFC.getMessage();

            } else if (Friendships.FRIENDSHIP_AGREED.equals(friendshipsFC.getStatus())) {
                //???????????????????????????????????????????????? ??????????????????-?????????????????????????????????
                cfStatus = Friendships.FRIENDSHIP_AGREED;
                fcStatus = Friendships.FRIENDSHIP_AGREED;
                message = friendshipsFC.getMessage();
                timestamp = friendshipsFC.getTimestamp();

            } else if (judgeComplexCondition(friendshipsCF, friendshipsFC, now_1, now_3, cfUpdateAtCal)) {
                cfStatus = Friendships.FRIENDSHIP_REQUESTING;
                fcStatus = Friendships.FRIENDSHIP_REQUESTED;
                action = "Sent";
                resultMessage = "Request sent.";
            } else {
                action = "None";
                resultMessage = "Do nothing.";
                return new InviteDTO(action, resultMessage);
            }
            //??????????????????
            doAddFriend0(message, friendshipsCF, friendshipsFC, timestamp, cfStatus, fcStatus);

            //??????????????????dataversion???????????????????????????
            refreshFriendshipVersion(currentUserId, timestamp);

            if (Friendships.FRIENDSHIP_REQUESTED.equals(friendshipsFC.getStatus())) {
                //????????????dataversion???????????????????????????
                refreshFriendshipVersion(friendId, timestamp);
                //????????????????????????
                String currentUserNickName = usersService.getCurrentUserNickNameWithCache(currentUserId);
                //??????????????????????????????
                try {
                    rongCloudClient.sendContactNotification(N3d.encode(currentUserId), currentUserNickName, new String[]{N3d.encode(friendId)}, N3d.encode(friendId), Constants.CONTACT_OPERATION_REQUEST, message, timestamp);
                } catch (Exception e) {
                    log.error("Error: send contact notification failed:" + e.getMessage(), e);
                }

                //????????????
                CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + currentUserId);
                CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + friendId);
                //??????Sent ?????????
                return new InviteDTO(action, resultMessage);
            } else {
                //???????????????
                removeBlackList(currentUserId, friendId);
                //????????????
                CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + currentUserId);
                CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + currentUserId);
                //??????
                return new InviteDTO(action, resultMessage);
            }
        } else {
            //????????????????????????????????????????????????????????????????????????????????????????????????
            if (currentUserId.equals(friendId)) {
                //?????????????????????,??????????????????????????????
                Friendships friendship = new Friendships();
                friendship.setUserId(currentUserId);
                friendship.setFriendId(friendId);
                friendship.setStatus(Friendships.FRIENDSHIP_AGREED);
                friendship.setTimestamp(timestamp);
                friendshipsService.saveSelective(friendship);
                refreshFriendshipVersion(currentUserId, timestamp);
                //????????????
                CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + currentUserId);
                CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + currentUserId);
                //??????
                return new InviteDTO(action, resultMessage);
            } else {
                //????????????????????????
                doAddFriend1(currentUserId, friendId, message, timestamp);
                //??????????????????????????????
                refreshFriendshipVersion(currentUserId, timestamp);
                refreshFriendshipVersion(friendId, timestamp);
                //????????????????????????
                String currentUserNickName = usersService.getCurrentUserNickNameWithCache(currentUserId);

                try {
                    //??????????????????????????????
                    rongCloudClient.sendContactNotification(N3d.encode(currentUserId), currentUserNickName, new String[]{N3d.encode(friendId)}, N3d.encode(friendId), Constants.CONTACT_OPERATION_REQUEST, message, timestamp);
                } catch (Exception e) {
                    log.error("Error: send contact notification failed:" + e.getMessage(), e);
                }

                //????????????
                CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + currentUserId);
                CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + friendId);
                //??????Sent ?????????
                action = "Sent";
                resultMessage = "Request sent.";
                return new InviteDTO(action, resultMessage);
            }
        }
    }

    /**
     * ??????????????????
     *
     * @param currentUserId
     * @param friendId
     * @param message
     * @param timestamp
     */
    private void doAddFriend1(Integer currentUserId, Integer friendId, String message, long timestamp) {
        transactionTemplate.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus transactionStatus) {
                //???????????????????????????????????????FRIENDSHIP_REQUESTING ?????????
                Friendships friendship = new Friendships();
                friendship.setUserId(currentUserId);
                friendship.setFriendId(friendId);
                friendship.setStatus(Friendships.FRIENDSHIP_REQUESTING);
                friendship.setMessage("");
                friendship.setTimestamp(timestamp);
                friendship.setCreatedAt(new Date());
                friendship.setUpdatedAt(friendship.getCreatedAt());
                friendshipsService.saveSelective(friendship);

                //?????????????????????????????????FRIENDSHIP_REQUESTED ?????????
                Friendships friendship2 = new Friendships();
                friendship2.setUserId(friendId);
                friendship2.setFriendId(currentUserId);
                friendship2.setStatus(Friendships.FRIENDSHIP_REQUESTED);
                friendship2.setMessage(message);
                friendship2.setTimestamp(timestamp);
                friendship2.setCreatedAt(new Date());
                friendship2.setUpdatedAt(friendship2.getCreatedAt());
                friendshipsService.saveSelective(friendship2);
                return true;
            }
        });

    }

    /**
     * ??????????????????
     *
     * @param message
     * @param friendshipsCF
     * @param friendshipsFC
     * @param timestamp
     * @param cfStatus
     * @param fcStatus
     */
    private void doAddFriend0(String message, Friendships friendshipsCF, Friendships friendshipsFC, long timestamp, int cfStatus, int fcStatus) {
        transactionTemplate.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus transactionStatus) {
                friendshipsCF.setTimestamp(timestamp);
                friendshipsCF.setStatus(cfStatus);
                friendshipsService.updateByPrimaryKeySelective(friendshipsCF);

                friendshipsFC.setTimestamp(timestamp);
                friendshipsFC.setStatus(fcStatus);
                friendshipsFC.setMessage(message);
                friendshipsService.updateByPrimaryKeySelective(friendshipsFC);
                return true;
            }
        });
    }

    private InviteDTO addNoNeedVerifyFriend(Integer currentUserId, Integer friendId, String message) throws ServiceException {
        String action = NONE;
        String resultMessage = "";
        long timestamp = System.currentTimeMillis();

        //???????????????
        removeBlackList(currentUserId, friendId);

        //??????????????????
        doAddFriend2(currentUserId, friendId, message, timestamp);

        //????????????????????????
        String currentUserNickName = usersService.getCurrentUserNickNameWithCache(currentUserId);
        try {
            //??????????????????????????????
            rongCloudClient.sendContactNotification(N3d.encode(currentUserId), currentUserNickName, new String[]{N3d.encode(friendId)}, N3d.encode(friendId), Constants.CONTACT_OPERATION_REQUEST, message, timestamp);
        } catch (Exception e) {
            log.error("Error: send contact notification failed:" + e.getMessage(), e);
        }
        //????????????
        CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + currentUserId);
        CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + friendId);
        //??????Sent ?????????
        action = "AddDirectly";
        resultMessage = "Request sent.";
        return new InviteDTO(action, resultMessage);

    }

    private void doAddFriend2(Integer currentUserId, Integer friendId, String message, long timestamp) {

        transactionTemplate.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus transactionStatus) {
                //???????????????????????????????????????????????????FRIENDSHIP_AGREED
                Friendships friendship = new Friendships();
                friendship.setUserId(currentUserId);
                friendship.setFriendId(friendId);
                friendship.setMessage(message);
                friendship.setStatus(Friendships.FRIENDSHIP_AGREED);
                friendship.setTimestamp(timestamp);
                friendship.setCreatedAt(new Date());
                friendship.setUpdatedAt(friendship.getCreatedAt());

                friendshipsService.saveOrUpdate(friendship, currentUserId, friendId);

                //???????????????????????????????????????????????????FRIENDSHIP_AGREED
                Friendships friendship2 = new Friendships();
                friendship2.setUserId(friendId);
                friendship2.setFriendId(currentUserId);
                friendship2.setMessage(message);
                friendship2.setStatus(Friendships.FRIENDSHIP_AGREED);
                friendship2.setTimestamp(timestamp);
                friendship2.setCreatedAt(new Date());
                friendship2.setUpdatedAt(friendship2.getCreatedAt());
                friendshipsService.saveOrUpdate(friendship2, friendId, currentUserId);
                return true;
            }
        });
    }

    private void updateBlackListStatus(Integer currentUserId, Integer friendId) {
        BlackLists bl = new BlackLists();
        bl.setFriendId(friendId);
        bl.setUserId(currentUserId);
        bl.setStatus(BlackLists.STATUS_INVALID);
        Example example = new Example(BlackLists.class);
        example.createCriteria().andEqualTo("userId", currentUserId)
                .andEqualTo("friendId", friendId);
        blackListsService.updateByExampleSelective(bl, example);
    }

    /**
     * ??????dataVersions?????????????????????
     *
     * @param userId
     */
    private void refreshFriendshipVersion(Integer userId, long timestamp) {
        DataVersions dataVersions = new DataVersions();
        dataVersions.setUserId(userId);
        dataVersions.setFriendshipVersion(timestamp);
        dataVersionsService.updateByPrimaryKeySelective(dataVersions);
    }

    /**
     * ??????????????????
     *
     * @param friendshipsCF
     * @param friendshipsFC
     * @param now_1
     * @param now_3
     * @param cfUpdateAtCal
     * @return
     */
    private boolean judgeComplexCondition(Friendships friendshipsCF, Friendships friendshipsFC, Calendar now_1, Calendar now_3, Calendar cfUpdateAtCal) {
        return (
                //??????1 ??????????????????????????????????????????
                //????????????????????????
                Friendships.FRIENDSHIP_DELETED.equals(friendshipsFC.getStatus()) &&
                        Friendships.FRIENDSHIP_DELETED.equals(friendshipsCF.getStatus())
        ) || (
                //??????2  ?????????????????????????????????????????????????????????
                //????????????????????????
                Friendships.FRIENDSHIP_DELETED.equals(friendshipsFC.getStatus()) &&
                        Friendships.FRIENDSHIP_AGREED.equals(friendshipsCF.getStatus())
        ) || (
                //??????3 ?????????????????????3?????????????????????1??????
                Friendships.FRIENDSHIP_REQUESTING.equals(friendshipsCF.getStatus()) &&
                        Friendships.FRIENDSHIP_IGNORED.equals(friendshipsFC.getStatus()) &&
                        now_1.after(cfUpdateAtCal)
        ) || (
                //??????4 ?????????????????????3?????????????????????1??????
                Friendships.FRIENDSHIP_REQUESTING.equals(friendshipsCF.getStatus()) &&
                        Friendships.FRIENDSHIP_REQUESTED.equals(friendshipsFC.getStatus()) &&
                        now_3.after(cfUpdateAtCal)
        );
    }


    /**
     * ??????????????????
     *
     * @param currentUserId
     * @param friendId
     * @throws ServiceException
     */
    public void agree(Integer currentUserId, Integer friendId) throws ServiceException {
        long timestamp = System.currentTimeMillis();
        //???????????????
        removeBlackList(currentUserId, friendId);
        doAgree0(currentUserId, friendId, timestamp);

        //??????????????????????????????
        refreshFriendshipVersion(currentUserId, timestamp);
        refreshFriendshipVersion(friendId, timestamp);

        //????????????????????????
        String currentUserNickName = usersService.getCurrentUserNickNameWithCache(currentUserId);
        //??????????????????????????????
        rongCloudClient.sendContactNotification(N3d.encode(currentUserId), currentUserNickName, new String[]{N3d.encode(friendId)}, N3d.encode(friendId), Constants.CONTACT_OPERATION_ACCEPT_RESPONSE, "", timestamp);
        //????????????
        CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + currentUserId);
        CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + friendId);
        return;
    }

    private void doAgree0(Integer currentUserId, Integer friendId, long timestamp) {

        transactionTemplate.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus transactionStatus) {

                //????????????????????????????????????
                int effectedCount = friendshipsService.updateAgreeStatus(currentUserId, friendId, timestamp, ImmutableList.of(Friendships.FRIENDSHIP_REQUESTED, Friendships.FRIENDSHIP_AGREED));
                if (effectedCount == 0) {
                    throw new ServiceRuntimeException(ErrorCode.UNKNOW_FRIEND_USER_OR_INVALID_STATUS);
                }
                //????????????Friendship??????????????????????????????FRIENDSHIP_AGREED
                friendshipsService.updateAgreeStatus(friendId, currentUserId, timestamp, null);
                return true;
            }
        });
    }

    /**
     * ???????????????
     * 1????????????????????????????????????
     * 2???????????????????????????????????????
     * 3?????????USER_BLACKLIST_CACHE_PREFIX ??????
     *
     * @param currentUserId
     * @param friendId
     * @throws ServiceException
     */
    private void removeBlackList(Integer currentUserId, Integer friendId) throws ServiceException {
        rongCloudClient.removeUserBlackList(N3d.encode(currentUserId), new String[]{N3d.encode(friendId)});
        rongCloudClient.removeUserBlackList(N3d.encode(friendId), new String[]{N3d.encode(currentUserId)});
        updateBlackListStatus(currentUserId, friendId);
        updateBlackListStatus(friendId, currentUserId);
        CacheUtil.delete(CacheUtil.USER_BLACKLIST_CACHE_PREFIX + currentUserId);
    }

    /**
     * ??????????????????
     *
     * @param currentUserId
     * @param friendId
     * @throws ServiceException
     */
    public void ignore(Integer currentUserId, Integer friendId) throws ServiceException {

        long timestamp = System.currentTimeMillis();
        //????????????Friendship??????????????????????????????FRIENDSHIP_IGNORED
        Friendships friendships = new Friendships();
        friendships.setStatus(Friendships.FRIENDSHIP_IGNORED);
        friendships.setTimestamp(timestamp);
        Example example = new Example(Friendships.class);
        example.createCriteria().andEqualTo("status", Friendships.FRIENDSHIP_REQUESTED)
                .andEqualTo("userId", currentUserId)
                .andEqualTo("friendId", friendId);

        int effectedCount = friendshipsService.updateByExampleSelective(friendships, example);
        if (effectedCount == 0) {
            throw new ServiceException(ErrorCode.UNKNOW_FRIEND_USER_OR_INVALID_STATUS, "Unknown friend user or invalid status.");
        }
        refreshFriendshipVersion(currentUserId, timestamp);
        CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + currentUserId);
        CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + friendId);

        return;
    }

    /**
     * ????????????
     *
     * @param currentUserId
     * @param friendId
     */
    public void delete(Integer currentUserId, Integer friendId) throws ServiceException {

        long timestamp = System.currentTimeMillis();

        Friendships friendships = new Friendships();
        friendships.setStatus(Friendships.FRIENDSHIP_DELETED);
        friendships.setDisplayName("");
        friendships.setMessage("");
        friendships.setTimestamp(timestamp);

        Example example = new Example(Friendships.class);
        example.createCriteria().andEqualTo("userId", currentUserId)
                .andEqualTo("friendId", friendId)
                .andIn("status", ImmutableList.of(Friendships.FRIENDSHIP_AGREED, Friendships.FRIENDSHIP_PULLEDBLACK));
        int effectedCount = friendshipsService.updateByExampleSelective(friendships, example);

        if (effectedCount == 0) {
            throw new ServiceException(ErrorCode.UNKNOW_FRIEND_USER_OR_INVALID_STATUS, "Unknown friend user or invalid status.");
        }

        refreshFriendshipVersion(currentUserId, timestamp);

        Users u = usersService.getByPrimaryKey(friendId);

        if (u != null) {
            //?????????????????????????????????,?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
            rongCloudClient.addUserBlackList(N3d.encode(currentUserId), new String[]{N3d.encode(friendId)});
            //???????????????????????????????????????
            blackListsService.saveOrUpdate(currentUserId, friendId, BlackLists.STATUS_VALID, timestamp);
            //?????????????????????
            dataVersionsService.updateBlacklistVersion(currentUserId, timestamp);

            //?????????????????????
            CacheUtil.delete(CacheUtil.USER_BLACKLIST_CACHE_PREFIX + currentUserId);

            //???????????????????????????FRIENDSHIP_DELETED
            Friendships friendships2 = new Friendships();
            friendships2.setStatus(Friendships.FRIENDSHIP_DELETED);
            friendships2.setDisplayName("");
            friendships2.setMessage("");
            friendships2.setTimestamp(timestamp);

            Example example2 = new Example(Friendships.class);
            example2.createCriteria().andEqualTo("userId", currentUserId)
                    .andEqualTo("friendId", friendId)
                    .andIn("status", ImmutableList.of(Friendships.FRIENDSHIP_AGREED));
            friendshipsService.updateByExampleSelective(friendships2, example2);

            //??????????????????
            CacheUtil.delete(CacheUtil.FRIENDSHIP_PROFILE_USER_CACHE_PREFIX + currentUserId + "_" + friendId);
            CacheUtil.delete(CacheUtil.FRIENDSHIP_PROFILE_CACHE_PREFIX + currentUserId + "_" + friendId);
            CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + currentUserId);
            CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + friendId);

            return;
        } else {
            throw new ServiceException(ErrorCode.UNKNOW_FRIEND_USER_OR_INVALID_STATUS);
        }

    }

    /**
     * ?????????????????????
     *
     * @param currentUserId
     * @param friendId
     * @param displayName
     */
    public void setDisplayName(Integer currentUserId, Integer friendId, String displayName) throws ServiceException {
        long timestamp = System.currentTimeMillis();

        //??????????????????
        Friendships friendships = new Friendships();
        friendships.setDisplayName(displayName);
        friendships.setTimestamp(timestamp);

        Example example = new Example(Friendships.class);
        example.createCriteria().andEqualTo("userId", currentUserId)
                .andEqualTo("friendId", friendId)
                .andEqualTo("status", Friendships.FRIENDSHIP_AGREED);
        int effectedCount = friendshipsService.updateByExampleSelective(friendships, example);
        if (effectedCount == 0) {
            throw new ServiceException(ErrorCode.UNKNOW_FRIEND_USER_OR_INVALID_STATUS, "Unknown friend user or invalid status.");
        }

        //????????????????????????
        refreshFriendshipVersion(currentUserId, timestamp);

        //????????????
        CacheUtil.delete(CacheUtil.FRIENDSHIP_PROFILE_CACHE_PREFIX + currentUserId + "_" + friendId);
        CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + currentUserId);
        return;

    }

    /**
     * ??????????????????????????????
     *
     * @param currentUserId
     * @return
     */
    public List<Friendships> getFriendList(Integer currentUserId) throws ServiceException {

        String result = CacheUtil.get(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + currentUserId);

        if (result != null) {
            return JacksonUtil.fromJson(result, List.class, Friendships.class);
        } else {
            List<Friendships> friendships = friendshipsService.getFriendShipListWithUsers(currentUserId);
            result = JacksonUtil.toJson(friendships);
            CacheUtil.set(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + currentUserId, result);
            return friendships;
        }
    }

    /**
     * ??????????????????
     *
     * @param currentUserId
     * @param friendId
     * @return
     */
    public Friendships getFriendProfile(Integer currentUserId, Integer friendId) throws ServiceException {


        String result = CacheUtil.get(CacheUtil.FRIENDSHIP_PROFILE_CACHE_PREFIX + currentUserId + "_" + friendId);

        if (!StringUtils.isEmpty(result)) {
            return JacksonUtil.fromJson(result, Friendships.class);
        }

        Friendships friendships = friendshipsService.getFriendShipWithUsers(currentUserId, friendId, Friendships.FRIENDSHIP_AGREED);

        if (friendships == null) {
            throw new ServiceException(ErrorCode.NOT_FRIEND_USER, "Current user is not friend of user " + currentUserId + ".");
        } else {
            result = JacksonUtil.toJson(friendships);
            CacheUtil.set(CacheUtil.FRIENDSHIP_PROFILE_CACHE_PREFIX + currentUserId + "_" + friendId, result);

            CacheUtil.set(CacheUtil.FRIENDSHIP_PROFILE_USER_CACHE_PREFIX + currentUserId + "_" + friendId, JacksonUtil.toJson(friendships.getUsers()));
            return friendships;
        }
    }

    /**
     * ?????????????????????????????????
     *
     * @param currentUserId
     * @param contactList
     */
    public List<ContractInfoDTO> getContactsInfo(Integer currentUserId, String[] contactList) throws ServiceException {

        List<ContractInfoDTO> contractInfoDTOList = new ArrayList<>();

        //??????????????????????????????????????????????????????????????????
        Example example = new Example(Users.class);
        example.createCriteria().andIn("phone", CollectionUtils.arrayToList(contactList));
        example.selectProperties("id", "phone", "nickname", "portraitUri", "stAccount");
        List<Users> usersList = usersService.getByExample(example);

        List<Integer> registerUserIdList = new ArrayList<>();
        Map<String, Users> registerUsers = new HashMap<>();

        List<Integer> friendIdList = new ArrayList<>();

        if (!CollectionUtils.isEmpty(usersList)) {
            for (Users users : usersList) {
                registerUserIdList.add(users.getId());
                registerUsers.put(users.getPhone(), users);
            }
            //?????????????????????????????????????????????
            Example friendShipExample = new Example(Friendships.class);
            friendShipExample.createCriteria().andEqualTo("userId", currentUserId)
                    .andEqualTo("status", Friendships.FRIENDSHIP_AGREED)
                    .andIn("friendId", registerUserIdList);
            friendShipExample.selectProperties("friendId");
            List<Friendships> friendshipsList = friendshipsService.getByExample(friendShipExample);

            if (!CollectionUtils.isEmpty(friendshipsList)) {
                for (Friendships friendships : friendshipsList) {
                    friendIdList.add(friendships.getFriendId());
                }
            }
        }

        //merge??????
        for (String phone : contactList) {
            ContractInfoDTO contractInfoDTO = new ContractInfoDTO();
            Users users = registerUsers.get(phone);
            if (users == null) {
                contractInfoDTO.setRegistered(ContractInfoDTO.UN_REGISTERED);
                contractInfoDTO.setRelationship(ContractInfoDTO.NON_FRIEND);
                contractInfoDTO.setStAccount("");
                contractInfoDTO.setPhone(phone);
                contractInfoDTO.setId("");
                contractInfoDTO.setNickname("");
                contractInfoDTO.setPortraitUri("");

            } else {
                contractInfoDTO.setRegistered(ContractInfoDTO.REGISTERED);
                if (friendIdList.contains(users.getId())) {
                    contractInfoDTO.setRelationship(ContractInfoDTO.IS_FRIEND);
                } else {
                    contractInfoDTO.setRelationship(ContractInfoDTO.NON_FRIEND);
                }
                contractInfoDTO.setId(N3d.encode(users.getId()));
                contractInfoDTO.setNickname(users.getNickname());
                contractInfoDTO.setPortraitUri(users.getPortraitUri());
                contractInfoDTO.setStAccount(users.getStAccount());
                contractInfoDTO.setPhone(phone);
            }
            contractInfoDTOList.add(contractInfoDTO);
        }
        return contractInfoDTOList;
    }

    public void batchDelete(Integer currentUserId, List<Integer> friendIds) throws ServiceException {

        long timestamp = System.currentTimeMillis();
        Friendships friendships = new Friendships();
        friendships.setStatus(Friendships.FRIENDSHIP_DELETED);
        friendships.setDisplayName("");
        friendships.setMessage("");
        friendships.setTimestamp(timestamp);

        Example example = new Example(Friendships.class);
        example.createCriteria().andEqualTo("status", Friendships.FRIENDSHIP_AGREED)
                .andEqualTo("userId", currentUserId)
                .andIn("friendId", friendIds);

        friendshipsService.updateByExampleSelective(friendships, example);

        //???????????????????????? IM ?????????
        String[] encodeFriendIds = MiscUtils.encodeIds(friendIds);

        rongCloudClient.addUserBlackList(N3d.encode(currentUserId), encodeFriendIds);
        CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + currentUserId);
        return;
    }

    /**
     * ???????????????????????????
     *
     * @param friendId
     * @param displayName
     * @param region
     * @param phone
     * @param description
     * @param imageUri
     */
    public void setFriendDescription(Integer currentUserId, Integer friendId, String displayName, String region, String phone, String description, String imageUri) throws ServiceException {

        Example example = new Example(Friendships.class);
        example.createCriteria().andEqualTo("userId", currentUserId)
                .andEqualTo("friendId", friendId);

        Friendships friendships = friendshipsService.getOneByExample(example);

        if (friendships != null) {

            Friendships newFriendships = new Friendships();
            if (displayName != null) {
                newFriendships.setDisplayName(displayName);
            }

            if (region != null && phone != null) {
                newFriendships.setRegion(region);
                newFriendships.setPhone(phone);
            }

            if (description != null) {
                newFriendships.setDescription(description);
            }

            if (imageUri != null) {
                newFriendships.setImageUri(imageUri);
            }

            friendshipsService.updateByExampleSelective(newFriendships, example);
            CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + currentUserId);

            CacheUtil.delete(CacheUtil.FRIENDSHIP_PROFILE_CACHE_PREFIX + currentUserId + "_" + friendId);

            return;
        } else {
            throw new ServiceException(ErrorCode.ILLEGAL_PARAMETER);
        }

    }

    /**
     * ???????????????????????????
     *
     * @param currentUserId
     * @param friendId
     */
    public FriendDTO getFriendDescription(Integer currentUserId, Integer friendId) {
        FriendDTO friendDTO = new FriendDTO();
        Example example = new Example(Friendships.class);
        example.createCriteria().andEqualTo("userId", currentUserId)
                .andEqualTo("friendId", friendId);
        example.selectProperties("displayName", "region", "phone", "description", "imageUri");

        Friendships friendships = friendshipsService.getOneByExample(example);

        if (friendships != null) {
            friendDTO.setDescription(friendships.getDescription());
            friendDTO.setDisplayName(friendships.getDisplayName());
            friendDTO.setImageUri(friendships.getImageUri());
            friendDTO.setRegion(friendships.getRegion());
            friendDTO.setPhone(friendships.getPhone());
        }
        return friendDTO;
    }
}
