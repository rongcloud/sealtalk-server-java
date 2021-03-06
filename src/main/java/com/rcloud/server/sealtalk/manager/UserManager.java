package com.rcloud.server.sealtalk.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.qiniu.util.Auth;
import com.rcloud.server.sealtalk.configuration.ProfileConfig;
import com.rcloud.server.sealtalk.constant.Constants;
import com.rcloud.server.sealtalk.constant.ErrorCode;
import com.rcloud.server.sealtalk.constant.SmsServiceType;
import com.rcloud.server.sealtalk.domain.*;
import com.rcloud.server.sealtalk.exception.ServiceException;
import com.rcloud.server.sealtalk.model.ServerApiParams;
import com.rcloud.server.sealtalk.model.dto.sync.*;
import com.rcloud.server.sealtalk.rongcloud.RongCloudClient;
import com.rcloud.server.sealtalk.service.*;
import com.rcloud.server.sealtalk.sms.SmsService;
import com.rcloud.server.sealtalk.sms.SmsServiceFactory;
import com.rcloud.server.sealtalk.spi.verifycode.VerifyCodeAuthentication;
import com.rcloud.server.sealtalk.spi.verifycode.VerifyCodeAuthenticationFactory;
import com.rcloud.server.sealtalk.util.*;
import io.micrometer.core.instrument.util.IOUtils;
import io.rong.models.Result;
import io.rong.models.response.BlackListResult;
import io.rong.models.response.TokenResult;
import io.rong.models.user.UserModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import tk.mybatis.mapper.entity.Example;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * @Author: xiuwei.nie
 * @Author: Jianlu.Yu
 * @Date: 2020/7/7
 * @Description:
 * @Copyright (c) 2020, rongcloud.cn All Rights Reserved
 */
@Service
@Slf4j
public class UserManager extends BaseManager {

    @Resource
    private ProfileConfig profileConfig;

    @Resource
    private RongCloudClient rongCloudClient;

    @Resource
    private VerificationCodesService verificationCodesService;

    @Resource
    private VerificationViolationsService verificationViolationsService;

    @Resource
    private UsersService usersService;

    @Resource
    private DataVersionsService dataVersionsService;

    @Resource
    private GroupMembersService groupMembersService;

    @Resource
    private FriendshipsService friendshipsService;

    @Resource
    private BlackListsService blackListsService;

    @Resource
    private GroupFavsService groupFavsService;

    @Value("classpath:region.json")
    private org.springframework.core.io.Resource regionResource;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private HttpClient httpClient;

    /**
     * ????????????????????????
     */
    public void sendCode(String region, String phone, SmsServiceType smsServiceType, ServerApiParams serverApiParams) throws ServiceException {
        log.info("send code. region:[{}] phone:[{}] smsServiceType:[{}]", region, phone, smsServiceType.getCode());

        region = MiscUtils.removeRegionPrefix(region);
        ValidateUtils.checkRegion(region);
        ValidateUtils.checkCompletePhone(phone);

        String ip = serverApiParams.getRequestUriInfo().getIp();

        // ???????????????????????????????????????
        VerificationCodes verificationCodes = verificationCodesService.getByRegionAndPhone(region, phone);
        if (verificationCodes != null) {
            checkLimitTime(verificationCodes);
        }

        if (SmsServiceType.YUNPIAN.equals(smsServiceType)) {
            //????????????????????????????????????IP??????????????????
            checkRequestFrequency(ip);
        }

        //???????????????verificationCodes??? ???????????????
        upsertAndSendToSms(region, phone, smsServiceType);

        //??????????????????????????? ????????????????????????????????????
        if (SmsServiceType.YUNPIAN.equals(smsServiceType)) {
            refreshRequestFrequency(ip);
        }
    }

    /**
     * ??????ip????????????
     *
     * @param ip
     */
    private void refreshRequestFrequency(String ip) {
        //??????verification_violations ip???????????????????????????
        VerificationViolations verificationViolations = verificationViolationsService.getByPrimaryKey(ip);
        if (verificationViolations == null) {
            verificationViolations = new VerificationViolations();
            verificationViolations.setIp(ip);
            verificationViolations.setCount(1);
            verificationViolations.setTime(new Date());
            verificationViolationsService.saveSelective(verificationViolations);
        } else {
            DateTime dateTime = new DateTime(new Date());
            dateTime = dateTime.minusHours(sealtalkConfig.getYunpianLimitedTime());
            Date limitDate = dateTime.toDate();
            if (limitDate.after(verificationViolations.getTime())) {
                //?????????????????????????????????1???????????????????????????????????????
                verificationViolations.setCount(1);
                verificationViolations.setTime(new Date());
            } else {
                verificationViolations.setCount(verificationViolations.getCount() + 1);
            }
            verificationViolationsService.updateByPrimaryKeySelective(verificationViolations);
        }

    }

    /**
     * ??????????????????????????????
     */
    private VerificationCodes upsertAndSendToSms(String region, String phone, SmsServiceType smsServiceType) throws ServiceException {
        if (Constants.ENV_DEV.equals(sealtalkConfig.getConfigEnv())) {
            //?????????????????????????????????????????????????????????
            return verificationCodesService.saveOrUpdate(region, phone, "");
        } else {
            SmsService smsService = SmsServiceFactory.getSmsService(smsServiceType);
            String sessionId = smsService.sendVerificationCode(region, phone);
            return verificationCodesService.saveOrUpdate(region, phone, sessionId);
        }
    }

    /**
     * ????????????????????????
     * ??????????????????5??????  ??????????????????????????????
     * ??????????????????1????????? ??????????????????????????????
     *
     * @param verificationCodes
     * @throws ServiceException
     */
    private void checkLimitTime(VerificationCodes verificationCodes)
            throws ServiceException {

        DateTime dateTime = new DateTime(new Date());
        if (Constants.ENV_DEV.equals(sealtalkConfig.getConfigEnv())) {
            dateTime = dateTime.minusSeconds(50);
        } else {
            dateTime = dateTime.minusMinutes(1);
        }
        Date limitDate = dateTime.toDate();

        if (limitDate.before(verificationCodes.getUpdatedAt())) {
            throw new ServiceException(ErrorCode.LIMIT_ERROR);
        }
    }

    /**
     * IP????????????????????????
     *
     * @param ip
     * @throws ServiceException
     */
    private void checkRequestFrequency(String ip) throws ServiceException {
        Integer yunpianLimitedTime = sealtalkConfig.getYunpianLimitedTime();
        Integer yunpianLimitedCount = sealtalkConfig.getYunpianLimitedCount();

        if (yunpianLimitedTime == null) {
            yunpianLimitedTime = 1;
        }

        if (yunpianLimitedCount == null) {
            yunpianLimitedCount = 20;
        }

        VerificationViolations verificationViolations = verificationViolationsService.getByPrimaryKey(ip);
        if (verificationViolations == null) {
            return;
        }

        DateTime dateTime = new DateTime(new Date());
        Date sendDate = dateTime.minusHours(yunpianLimitedTime).toDate();

        boolean beyondLimit = verificationViolations.getCount() >= yunpianLimitedCount;

        //?????????????????????????????????????????????1???????????????????????????????????????????????????"Too many times sent"
        if (sendDate.before(verificationViolations.getTime()) && beyondLimit) {
            throw new ServiceException(ErrorCode.YUN_PIAN_SMS_ERROR);
        }
    }


    /**
     * ??????????????????????????????
     *
     * @param region
     * @param phone
     * @return true ?????????false ?????????
     * @throws ServiceException
     */
    public boolean isExistUser(String region, String phone) throws ServiceException {
        Users param = new Users();
        param.setRegion(region);
        param.setPhone(phone);
        Users users = usersService.getOne(param);
        return users != null;
    }


    /**
     * ???????????????
     *
     * @param region
     * @param phone
     * @param code
     * @param smsServiceType
     * @return
     * @throws ServiceException
     */
    public String verifyCode(String region, String phone, String code, SmsServiceType smsServiceType) throws ServiceException {

        VerificationCodes verificationCodes = verificationCodesService.getByRegionAndPhone(region, phone);
        VerifyCodeAuthentication verifyCodeAuthentication = VerifyCodeAuthenticationFactory.getVerifyCodeAuthentication(smsServiceType);
        verifyCodeAuthentication.validate(verificationCodes, code, sealtalkConfig.getConfigEnv());
        return verificationCodes.getToken();
    }

    public Integer register(String nickname, String password, String verificationToken) throws ServiceException {

        VerificationCodes verificationCodes = verificationCodesService.getByToken(verificationToken);

        if (verificationCodes == null) {
            throw new ServiceException(ErrorCode.UNKNOWN_VERIFICATION_TOKEN);
        }

        Users param = new Users();
        param.setRegion(verificationCodes.getRegion());
        param.setPhone(verificationCodes.getPhone());
        Users users = usersService.getOne(param);

        if (users != null) {
            throw new ServiceException(ErrorCode.PHONE_ALREADY_REGISTERED);
        }
        //??????????????????????????????hash
        int salt = RandomUtil.randomBetween(1000, 9999);
        String hashStr = MiscUtils.hash(password, salt);

        Users u = register0(nickname, verificationCodes.getRegion(), verificationCodes.getPhone(), salt, hashStr);

        //??????nickname
        CacheUtil.set(CacheUtil.NICK_NAME_CACHE_PREFIX + u.getId(), u.getNickname());

        return u.getId();
    }

    /**
     * ????????????user ??????dataversion???
     * ????????????
     *
     * @param nickname
     * @param region
     * @param phone
     * @param salt
     * @param hashStr
     * @return
     */
    private Users register0(String nickname, String region, String phone, int salt, String hashStr) {
        return transactionTemplate.execute(new TransactionCallback<Users>() {
            @Override
            public Users doInTransaction(TransactionStatus transactionStatus) {
                //??????user???
                Users u = new Users();
                u.setNickname(nickname);
                u.setRegion(region);
                u.setPhone(phone);
                u.setPasswordHash(hashStr);
                u.setPasswordSalt(String.valueOf(salt));
                u.setCreatedAt(new Date());
                u.setUpdatedAt(u.getCreatedAt());
                u.setPortraitUri(sealtalkConfig.getRongcloudDefaultPortraitUrl());

                usersService.saveSelective(u);


                //??????DataVersion???
                DataVersions dataVersions = new DataVersions();
                dataVersions.setUserId(u.getId());
                dataVersionsService.saveSelective(dataVersions);

                return u;
            }
        });

    }

    /**
     * ????????????
     *
     * @param region
     * @param phone
     * @param password
     * @return Pair<L, R> L=??????ID???R=??????token
     * @throws ServiceException
     */
    public Pair<Integer, String> login(String region, String phone, String password) throws ServiceException {

        Users param = new Users();
        param.setRegion(region);
        param.setPhone(phone);
        Users u = usersService.getOne(param);

        //????????????????????????
        if (u == null) {
            throw new ServiceException(ErrorCode.USER_NOT_EXIST);
        }

        //????????????????????????
        String passwordHash = MiscUtils.hash(password, Integer.valueOf(u.getPasswordSalt()));

        if (!passwordHash.equals(u.getPasswordHash())) {
            throw new ServiceException(ErrorCode.USER_PASSWORD_WRONG);
        }

        log.info("login id:" + u.getId() + " nickname:" + u.getNickname());
        //??????nickname
        CacheUtil.set(CacheUtil.NICK_NAME_CACHE_PREFIX + u.getId(), u.getNickname());

        //?????????????????????????????????,???????????????
        List<Groups> groupsList = new ArrayList<>();
        Map<String, String> idNamePariMap = new HashMap<>();
        List<GroupMembers> groupMembersList = groupMembersService.queryGroupMembersWithGroupByMemberId(u.getId());
        if (!CollectionUtils.isEmpty(groupMembersList)) {
            for (GroupMembers gm : groupMembersList) {
                Groups groups = gm.getGroups();
                if (groups != null) {
                    groupsList.add(groups);
                    idNamePariMap.put(N3d.encode(groups.getId()), groups.getName());
                }
            }
        }

        //?????????????????????
        log.info("'Sync groups: {}", idNamePariMap);

        //????????????sdk ??????????????????userid??????groupIdName?????????????????????
        try {
            Result result = rongCloudClient.syncGroupInfo(N3d.encode(u.getId()), groupsList);
            if (!Constants.CODE_OK.equals(result.getCode())) {
                log.error("Error sync user's group list failed,code:" + result.getCode());
            }
        } catch (Exception e) {
            log.error("Error sync user's group list error:" + e.getMessage(), e);
        }


        String token = u.getRongCloudToken();
        if (StringUtils.isEmpty(token)) {
            //??????user???????????????token?????????????????????sdk ??????token
            //?????????????????????????????????????????????????????????
            String portraitUri = StringUtils.isEmpty(u.getPortraitUri()) ? sealtalkConfig.getRongcloudDefaultPortraitUrl() : u.getPortraitUri();
            TokenResult tokenResult = rongCloudClient.register(N3d.encode(u.getId()), u.getNickname(), portraitUri);
            if (!Constants.CODE_OK.equals(tokenResult.getCode())) {
                throw new ServiceException(ErrorCode.SERVER_ERROR, "'RongCloud Server API Error Code: " + tokenResult.getCode());
            }

            token = tokenResult.getToken();

            //???????????????userId????????????token
            Users users = new Users();
            users.setId(u.getId());
            users.setRongCloudToken(token);
            users.setUpdatedAt(new Date());
            usersService.updateByPrimaryKeySelective(users);
        }

        //??????userId???token
        return Pair.of(u.getId(), token);
    }

    /**
     * ????????????
     *
     * @param password
     * @param verificationToken
     * @throws ServiceException
     */
    public void resetPassword(String password, String verificationToken) throws ServiceException {

        VerificationCodes verificationCodes = verificationCodesService.getByToken(verificationToken);

        if (verificationCodes == null) {
            throw new ServiceException(ErrorCode.UNKNOWN_VERIFICATION_TOKEN);
        }

        //?????????hash,??????user???????????????
        int salt = RandomUtil.randomBetween(1000, 9999);
        String hashStr = MiscUtils.hash(password, salt);

        updatePassword(verificationCodes.getRegion(), verificationCodes.getPhone(), salt, hashStr);
    }

    /**
     * ????????????
     *
     * @param newPassword
     * @param oldPassword
     * @param currentUserId
     * @throws ServiceException
     */
    public void changePassword(String newPassword, String oldPassword, Integer currentUserId) throws ServiceException {

        Users u = usersService.getByPrimaryKey(currentUserId);

        if (u == null) {
            throw new ServiceException(ErrorCode.REQUEST_ERROR);
        }

        String oldPasswordHash = MiscUtils.hash(oldPassword, Integer.valueOf(u.getPasswordSalt()));

        if (!oldPasswordHash.equals(u.getPasswordHash())) {
            throw new ServiceException(ErrorCode.USER_PASSWORD_WRONG_2);
        }

        //?????????hash,??????user???????????????
        int salt = RandomUtil.randomBetween(1000, 9999);
        String hashStr = MiscUtils.hash(newPassword, salt);

        updatePassword(u.getRegion(), u.getPhone(), salt, hashStr);
    }

    private void updatePassword(String region, String phone, int salt, String hashStr) {
        Users user = new Users();
        user.setPasswordHash(hashStr);
        user.setPasswordSalt(String.valueOf(salt));
        user.setUpdatedAt(new Date());

        Example example = new Example(Users.class);
        Example.Criteria criteria = example.createCriteria();
        criteria.andEqualTo("region", region);
        criteria.andEqualTo("phone", phone);
        usersService.updateByExampleSelective(user, example);
    }

    /**
     * ???????????????????????????
     *
     * @param nickname
     * @param currentUserId
     * @throws ServiceException
     */
    public void setNickName(String nickname, Integer currentUserId) throws ServiceException {
        long timestamp = System.currentTimeMillis();
        //????????????
        Users users = new Users();
        users.setId(currentUserId);
        users.setNickname(nickname);
        users.setTimestamp(timestamp);
        users.setUpdatedAt(new Date());
        usersService.updateByPrimaryKeySelective(users);

        //??????????????????????????????
        try {
            Result result = rongCloudClient.updateUser(N3d.encode(currentUserId), nickname, null);
            if (!result.getCode().equals(200)) {
                log.error("RongCloud Server API Error code: {},errorMessage: {}", result.getCode(), result.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("invoke rongCloudClient updateUser exception: " + e.getMessage(), e);
        }
        //??????????????????
        CacheUtil.set(CacheUtil.NICK_NAME_CACHE_PREFIX + currentUserId, nickname);

        //???????????????????????????
        clearCacheAndUpdateVersion(currentUserId, timestamp);

        return;
    }

    /**
     * ????????????????????????
     *
     * @param portraitUri
     * @param currentUserId
     * @throws ServiceException
     */
    public void setPortraitUri(String portraitUri, Integer currentUserId) throws ServiceException {

        long timestamp = System.currentTimeMillis();
        Users u = usersService.getByPrimaryKey(currentUserId);
        if (u == null) {
            throw new ServiceException(ErrorCode.REQUEST_ERROR);
        }
        //????????????
        Users users = new Users();
        users.setId(currentUserId);
        users.setPortraitUri(portraitUri);
        users.setTimestamp(timestamp);
        users.setUpdatedAt(new Date());
        usersService.updateByPrimaryKeySelective(users);

        //??????????????????????????????
        try {
            Result result = rongCloudClient.updateUser(N3d.encode(currentUserId), u.getNickname(), portraitUri);
            if (!result.getCode().equals(200)) {
                log.error("RongCloud Server API Error code: {},errorMessage: {}", result.getCode(), result.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("invoke rongCloudClient updateUser exception: " + e.getMessage(), e);
        }

        //???????????????????????????
        clearCacheAndUpdateVersion(currentUserId, timestamp);
        return;
    }

    /**
     * ??????user?????????????????????dataversion??????
     *
     * @param currentUserId
     */
    private void clearCacheAndUpdateVersion(Integer currentUserId, long timestamp) {
        //??????DataVersion?????? UserVersion
        DataVersions dataVersions = new DataVersions();
        dataVersions.setUserId(currentUserId);
        dataVersions.setUserVersion(timestamp);
        dataVersionsService.updateByPrimaryKeySelective(dataVersions);

        //??????DataVersion?????? AllFriendshipVersion
        dataVersionsService.updateAllFriendshipVersion(currentUserId, timestamp);

        //????????????"user_" + currentUserId
        //????????????"friendship_profile_user_" + currentUserId
        CacheUtil.delete(CacheUtil.USER_CACHE_PREFIX + currentUserId);
        CacheUtil.delete(CacheUtil.FRIENDSHIP_PROFILE_USER_CACHE_PREFIX + currentUserId);

        //?????????????????????????????????,???????????????friendship_all_+friendId
        Friendships f = new Friendships();
        f.setUserId(currentUserId);
        List<Friendships> friendshipsList = friendshipsService.get(f);
        if (!CollectionUtils.isEmpty(friendshipsList)) {
            for (Friendships friendships : friendshipsList) {
                CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + friendships.getFriendId());
            }
        }
        //????????????????????????groupid isDeleted: false
        //????????????group_members_
        Example example = new Example(GroupMembers.class);
        Example.Criteria criteria = example.createCriteria();
        criteria.andEqualTo("memberId", currentUserId);
        criteria.andEqualTo("isDeleted", GroupMembers.IS_DELETED_NO);

        List<GroupMembers> groupMembersList = groupMembersService.getByExample(example);
        if (!CollectionUtils.isEmpty(groupMembersList)) {
            for (GroupMembers groupMembers : groupMembersList) {
                CacheUtil.delete(CacheUtil.GROUP_MEMBERS_CACHE_PREFIX + groupMembers.getGroupId());
            }
        }
    }

    /**
     * ????????????token
     * 1?????????currentUserId??????User
     * 2??????????????????????????????????????????token
     * 3?????????userId??????????????????users??????rongCloudToken
     * 4??????userid???token???????????????
     */
    public Pair<Integer, String> getToken(Integer currentUserId) throws ServiceException {

        Users user = usersService.getByPrimaryKey(currentUserId);

        //???????????????????????????????????????token
        //?????????????????????????????????????????????????????????
        String portraitUri = StringUtils.isEmpty(user.getPortraitUri()) ? sealtalkConfig.getRongcloudDefaultPortraitUrl() : user.getPortraitUri();
        TokenResult tokenResult = rongCloudClient.register(N3d.encode(user.getId()), user.getNickname(), portraitUri);
        String token = tokenResult.getToken();

        //??????userId??????????????????users??????rongCloudToken
        Users param = new Users();
        param.setId(user.getId());
        param.setRongCloudToken(token);
        param.setUpdatedAt(new Date());
        usersService.updateByPrimaryKeySelective(param);

        return Pair.of(user.getId(), token);
    }

    /**
     * ?????????????????????
     * 1??????cookie?????????currentUserId
     * 2?????????currentUserId??????????????????????????????????????????????????????
     * 3??????????????????????????????????????????????????????????????????????????????????????????????????????
     * 4??????????????????????????????????????????merge??????????????????
     * 5????????????cache?????????????????????????????????
     *
     * @param currentUserId
     * @return
     * @throws ServiceException
     */
    public List<BlackLists> getBlackList(Integer currentUserId) throws ServiceException {

        long timestamp = System.currentTimeMillis();
        //??????????????????blacklist?????????????????????
        String blackListStr = CacheUtil.get(CacheUtil.USER_BLACKLIST_CACHE_PREFIX + currentUserId);
        if (!StringUtils.isEmpty(blackListStr)) {
            return JacksonUtil.fromJson(blackListStr, List.class, BlackLists.class);
        }
        //???????????????blacklist???
        List<BlackLists> dbBlackLists = blackListsService.getBlackListsWithFriendUsers(currentUserId);

        //???????????????????????????????????????
        BlackListResult blackListResult = rongCloudClient.queryUserBlackList(N3d.encode(currentUserId));

        UserModel[] serverBlackList = blackListResult.getUsers();

        List<Long> serverBlackListIds = new ArrayList<>();

        if (ArrayUtils.isNotEmpty(serverBlackList)) {
            for (UserModel userModel : serverBlackList) {
                long id = N3d.decode(userModel.getId());
                serverBlackListIds.add(id);
            }
        }

        List<Long> dbBlacklistUserIds = new ArrayList<>();

        boolean hasDirtyData = false;
        if (!CollectionUtils.isEmpty(dbBlackLists)) {
            for (BlackLists blackLists : dbBlackLists) {
                if (blackLists.getUsers() != null) {
                    Long userId = Long.valueOf(blackLists.getUsers().getId());
                    dbBlacklistUserIds.add(userId);
                } else {
                    hasDirtyData = true;
                }
            }
        }
        if (hasDirtyData) {
            log.error("Dirty blacklist data currentUserId:{}", currentUserId);
        }

        //????????????????????????????????????????????????????????????????????????
        //????????????????????????????????????????????????????????????
        if (!CollectionUtils.isEmpty(serverBlackListIds)) {
            for (Long serverId : serverBlackListIds) {
                //??????????????????????????????????????????TODO
                if (!dbBlacklistUserIds.contains(serverId)) {
                    blackListsService.saveOrUpdate(currentUserId, serverId.intValue(), BlackLists.STATUS_VALID, timestamp);
                    log.info("Sync: fix user blacklist, add {} -> {} from db.", currentUserId, serverId);
                    //?????????????????????
                    dataVersionsService.updateBlacklistVersion(currentUserId, timestamp);
                }
            }
        }

        //????????????????????????????????????????????????????????????????????????
        if (!CollectionUtils.isEmpty(dbBlacklistUserIds)) {
            for (Long userId : dbBlacklistUserIds) {
                if (!serverBlackListIds.contains(userId)) {

                    blackListsService.updateStatus(currentUserId, userId.intValue(), BlackLists.STATUS_INVALID, timestamp);
                    log.info("Sync: fix user blacklist, remove {} -> {} from db.", currentUserId, userId);

                    //?????????????????????
                    dataVersionsService.updateBlacklistVersion(currentUserId, timestamp);
                }
            }
        }

        //????????????
        CacheUtil.set(CacheUtil.USER_BLACKLIST_CACHE_PREFIX + currentUserId, JacksonUtil.toJson(dbBlackLists));

        return dbBlackLists;
    }


    /**
     * ????????????????????????
     * 1????????????????????????ID??????????????????????????????????????????404???friendId is not an available userId.
     * 2???????????????????????????????????????????????????
     * 3?????????????????????????????????????????????????????????????????????????????????
     * 4?????????????????????"user_blacklist_" + currentUserId
     * 5?????????Friendship ????????????????????????????????? FRIENDSHIP_BLACK = 31
     * 6???????????????friendship????????????
     * -???Cache.del("friendship_profile_displayName_" + currentUserId + "_" + friendId);
     * -???Cache.del("friendship_profile_user_" + currentUserId + "_" + friendId);
     * -???Cache.del("friendship_all_" + currentUserId);
     * -???Cache.del("friendship_all_" + friendId);
     */
    public void addBlackList(Integer currentUserId, Integer friendId, String encodedFriendId) throws ServiceException {

        long timestamp = System.currentTimeMillis();
        //??????friendId ??????????????????
        Users user = usersService.getByPrimaryKey(friendId);
        if (user == null) {
            throw new ServiceException(ErrorCode.FRIEND_USER_NOT_EXIST);
        }

        String[] blackFriendIds = {encodedFriendId};
        //???????????????????????????????????????
        rongCloudClient.addUserBlackList(N3d.encode(currentUserId), blackFriendIds);

        //????????????????????????????????????????????????
        blackListsService.saveOrUpdate(currentUserId, friendId, BlackLists.STATUS_VALID, timestamp);

        //???????????????????????????
        dataVersionsService.updateBlacklistVersion(currentUserId, timestamp);

        //??????user_blacklist_??????
        CacheUtil.delete(CacheUtil.USER_BLACKLIST_CACHE_PREFIX + currentUserId);

        //??????????????????????????????????????????
        friendshipsService.updateFriendShipBlacklistsStatus(currentUserId, friendId);

        //??????friendship????????????
        CacheUtil.delete(CacheUtil.FRIENDSHIP_PROFILE_CACHE_PREFIX + currentUserId + "_" + friendId);
        CacheUtil.delete(CacheUtil.FRIENDSHIP_PROFILE_USER_CACHE_PREFIX + currentUserId + "_" + friendId);
        CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + currentUserId);
        CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + friendId);

        return;
    }

    /**
     * ????????????????????????
     * 1??????????????????????????????????????????
     * 2???????????????Blacklist ????????????????????????status???false
     * 3???????????????DataVersion???BlacklistVersion ??????
     * 4???????????????user_blacklist_
     * 5?????????Friendship ?????????????????? FRIENDSHIP_AGREED = 20
     * 4???????????????????????????
     * -???Cache.del("friendship_profile_displayName_" + currentUserId + "_" + friendId);
     * -???Cache.del("friendship_profile_user_" + currentUserId + "_" + friendId);
     * -???Cache.del("friendship_all_" + currentUserId);
     * -??? Cache.del("friendship_all_" + friendId);
     *
     * @param currentUserId
     * @param friendId
     * @param encodedFriendId
     * @throws ServiceException
     */
    public void removeBlackList(Integer currentUserId, Integer friendId, String encodedFriendId) throws ServiceException {

        long timestamp = System.currentTimeMillis();

        String[] blackFriendIds = {encodedFriendId};
        //???????????????????????????????????????
        rongCloudClient.removeUserBlackList(N3d.encode(currentUserId), blackFriendIds);

        //????????????Blacklist ????????????????????????status???false
        blackListsService.updateStatus(currentUserId, friendId, BlackLists.STATUS_INVALID, timestamp);

        //???????????????????????????
        dataVersionsService.updateBlacklistVersion(currentUserId, timestamp);

        //????????????user_blacklist_
        CacheUtil.delete(CacheUtil.USER_BLACKLIST_CACHE_PREFIX + currentUserId);

        //??????Friendship ?????????????????? FRIENDSHIP_AGREED = 20
        friendshipsService.updateAgreeStatus(currentUserId, friendId, timestamp, ImmutableList.of(Friendships.FRIENDSHIP_PULLEDBLACK));
        log.info("result--remove db black currentUserId={},friendId={}", currentUserId, friendId);

        //??????friendship????????????
        CacheUtil.delete(CacheUtil.FRIENDSHIP_PROFILE_CACHE_PREFIX + currentUserId + "_" + friendId);
        CacheUtil.delete(CacheUtil.FRIENDSHIP_PROFILE_USER_CACHE_PREFIX + currentUserId + "_" + friendId);
        CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + currentUserId);
        CacheUtil.delete(CacheUtil.FRIENDSHIP_ALL_CACHE_PREFIX + friendId);
        log.info("result--remove cache black currentUserId={},friendId={}", currentUserId, friendId);

        return;
    }

    /**
     * ????????????????????????
     * -???????????????????????????????????????????????????db??????
     *
     * @param currentUserId
     * @return
     */
    public List<Groups> getGroups(Integer currentUserId) throws ServiceException {

        List<Groups> groupsList = new ArrayList<>();

        String groupsJson = CacheUtil.get(CacheUtil.USER_GROUP_CACHE_PREFIX + currentUserId);

        if (!StringUtils.isEmpty(groupsJson)) {
            return JacksonUtil.fromJson(groupsJson, List.class, Groups.class);
        }

        //???????????????????????????db
        List<GroupMembers> groupMembersList = groupMembersService.queryGroupMembersWithGroupByMemberId(currentUserId);

        if (!CollectionUtils.isEmpty(groupMembersList)) {
            for (GroupMembers groupMembers : groupMembersList) {
                groupsList.add(groupMembers.getGroups());
            }
        }
        CacheUtil.set(CacheUtil.USER_GROUP_CACHE_PREFIX + currentUserId, JacksonUtil.toJson(groupsList));
        return groupsList;
    }

    /**
     * ??????id??????????????????
     *
     * @param userId
     * @return
     */
    public Users getUser(Integer userId) {
        return usersService.getByPrimaryKey(userId);
    }

    /**
     * ??????userIds????????????????????????
     *
     * @param userIds
     * @return
     */
    public List<Users> getBatchUser(List<Integer> userIds) {

        Example example = new Example(Users.class);
        example.createCriteria().andIn("id", userIds);
        return usersService.getByExample(example);
    }

    /**
     * ??????stAccount??????????????????
     *
     * @param stAccount
     * @return
     */
    public Users getUserByStAccount(String stAccount) {
        Users u = new Users();
        u.setStAccount(stAccount);
        return usersService.getOne(u);
    }

    /**
     * ?????????????????????????????????
     *
     * @param region
     * @param phone
     * @return
     */
    public Users getUser(String region, String phone) {
        Users u = new Users();
        u.setRegion(region);
        u.setPhone(phone);
        return usersService.getOne(u);
    }

    /**
     * ??????????????????
     *
     * @param u
     */
    public void updateUserById(Users u) {
        usersService.updateByPrimaryKeySelective(u);
    }

    /**
     * ?????? SealTlk ???
     *
     * @param currentUserId
     * @param stAccount
     * @throws ServiceException
     */
    public void setStAccount(Integer currentUserId, String stAccount) throws ServiceException {
        Users u = new Users();
        u.setStAccount(stAccount);

        Users users = usersService.getOne(u);
        if (users != null) {
            throw new ServiceException(ErrorCode.USER_STACCOUNT_EXIST);
        }

        u.setId(currentUserId);
        usersService.updateByPrimaryKeySelective(u);

    }

    /**
     * ???????????????????????????
     *
     * @param userId
     * @param limit
     * @param offset
     * @return
     * @throws ServiceException
     */
    public Pair<Integer, List<Groups>> getFavGroups(Integer userId, Integer limit, Integer offset) throws ServiceException {
        List<Groups> groupsList = new ArrayList<>();
        Integer count = groupFavsService.queryCountGroupFavs(userId);
        if (count != null && count > 0) {
            List<GroupFavs> groupFavsList = groupFavsService.queryGroupFavsWithGroupByUserId(userId, limit, offset);
            if (!CollectionUtils.isEmpty(groupFavsList)) {
                for (GroupFavs groupFavs : groupFavsList) {
                    if (groupFavs.getGroups() != null) {
                        groupsList.add(groupFavs.getGroups());
                    }
                }
            }
        }

        return Pair.of(count, groupsList);
    }

    /**
     * ??????????????????
     *
     * @return
     * @throws ServiceException
     */
    public JsonNode getRegionList() throws ServiceException {
        try {
            String regionData = CacheUtil.get(CacheUtil.REGION_LIST_DATA);
            if (!StringUtils.isEmpty(regionData)) {
                return JacksonUtil.getJsonNode(regionData);
            }
            regionData = IOUtils
                    .toString(regionResource.getInputStream(), StandardCharsets.UTF_8);
            CacheUtil.set(CacheUtil.REGION_LIST_DATA, regionData);
            return JacksonUtil.getJsonNode(regionData);
        } catch (Exception e) {
            log.error("get Region resource error:" + e.getMessage(), e);
            throw new ServiceException(ErrorCode.INVALID_REGION_LIST);
        }

    }

    /**
     * ???????????????token
     */
    public String getImageToken() {

        String accessKey = sealtalkConfig.getQiniuAccessKey();
        String secretKey = sealtalkConfig.getQiniuSecretKey();
        String bucket = sealtalkConfig.getQiniuBucketName();
        Auth auth = Auth.create(accessKey, secretKey);
        String upToken = auth.uploadToken(bucket);
        return upToken;
    }

    /**
     * ???????????????????????????
     */
    public String getSmsImgCode() {

        //TODO ??????????????????sdk
        String result = httpClient.get(Constants.URL_GET_RONGCLOUD_IMG_CODE + sealtalkConfig.getRongcloudAppKey());
        return result;
    }


    /**
     * ???????????????????????????????????????????????????????????????
     *
     * @param currentUserId
     * @param version
     */
    public SyncInfoDTO getSyncInfo(Integer currentUserId, Long version) throws ServiceException {

        SyncInfoDTO syncInfoDTO = new SyncInfoDTO();

        //?????????????????????????????? userVersion???blacklistVersion???friendshipVersion???groupVersion???groupMemberVersion
        DataVersions dataVersions = dataVersionsService.getByPrimaryKey(currentUserId);

        Users users = null;
        List<BlackLists> blackListsList = new ArrayList<>();
        List<Friendships> friendshipsList = new ArrayList<>();
        List<GroupMembers> groupsList = new ArrayList<>();
        List<GroupMembers> groupMembersList = new ArrayList<>();

        if (dataVersions.getUserVersion() > version) {
            //??????????????????
            users = usersService.getByPrimaryKey(currentUserId);
        }

        if (dataVersions.getBlacklistVersion() > version) {
            //???????????????????????????
            blackListsList = blackListsService.getBlackListsWithFriendUsers(currentUserId, version);
        }

        if (dataVersions.getFriendshipVersion() > version) {
            friendshipsList = friendshipsService.getFriendShipListWithUsers(currentUserId, version);
        }

        List<Integer> groupIdList = new ArrayList<>();
        if (dataVersions.getGroupVersion() > version) {
            groupsList = groupMembersService.queryGroupMembersWithGroupByMemberId(currentUserId);
            if (!CollectionUtils.isEmpty(groupsList)) {
                for (GroupMembers groupMember : groupsList) {
                    if (groupMember.getGroups() != null) {
                        groupIdList.add(groupMember.getGroups().getId());
                    }
                }
            }
        }

        if (dataVersions.getGroupVersion() > version) {

            groupMembersList = groupMembersService.queryGroupMembersWithUsersByGroupIdsAndVersion(groupIdList, version);
        }

        Long maxVersion = 0L;
        if (users != null) {
            maxVersion = users.getTimestamp();
        }

        if (blackListsList != null) {
            for (BlackLists blackLists : blackListsList) {
                if (blackLists.getTimestamp() > maxVersion) {
                    maxVersion = blackLists.getTimestamp();
                }
            }
        }

        if (friendshipsList != null) {
            for (Friendships friendships : friendshipsList) {
                if (friendships.getTimestamp() > maxVersion) {
                    maxVersion = friendships.getTimestamp();
                }
            }
        }

        if (groupsList != null) {
            for (GroupMembers groupMembers : groupsList) {
                if (groupMembers.getGroups() != null) {
                    if (groupMembers.getGroups().getTimestamp() > maxVersion) {
                        maxVersion = groupMembers.getGroups().getTimestamp();
                    }
                }

            }
        }

        if (groupMembersList != null) {
            for (GroupMembers groupMembers : groupMembersList) {
                if (groupMembers.getTimestamp() > maxVersion) {
                    maxVersion = groupMembers.getTimestamp();
                }
            }
        }

        log.info("sync info ,maxVersion={}", maxVersion);

        syncInfoDTO.setVersion(version);


        SyncUserDTO userDTO = new SyncUserDTO();
        if(users!=null){
            userDTO.setId(N3d.encode(users.getId()));
            userDTO.setNickname(users.getNickname());
            userDTO.setPortraitUri(users.getPortraitUri());
            userDTO.setTimestamp(users.getTimestamp());
        }
        syncInfoDTO.setUser(userDTO);

        List<SyncBlackListDTO> syncBlackListDTOList = new ArrayList<>();
        if(!CollectionUtils.isEmpty(blackListsList)){
            for(BlackLists blackLists:blackListsList){
                SyncBlackListDTO syncBlackListDTO = new SyncBlackListDTO();
                syncBlackListDTO.setFriendId(N3d.encode(blackLists.getFriendId()));
                syncBlackListDTO.setStatus(BlackLists.STATUS_VALID.equals(blackLists.getStatus())?true:false);
                syncBlackListDTO.setTimestamp(blackLists.getTimestamp());

                Users u = blackLists.getUsers();
                SyncUserDTO sUser = new SyncUserDTO();

                if(u!=null){
                    sUser.setId(N3d.encode(u.getId()));
                    sUser.setNickname(u.getNickname());
                    sUser.setPortraitUri(u.getPortraitUri());
                    sUser.setTimestamp(u.getTimestamp());
                }
                syncBlackListDTO.setUser(sUser);
                syncBlackListDTOList.add(syncBlackListDTO);
            }
        }

        syncInfoDTO.setBlacklist(syncBlackListDTOList);


        List<SyncFriendshipDTO> syncFriendshipDTOList = new ArrayList<>();
        if(!CollectionUtils.isEmpty(friendshipsList)){
            for(Friendships friendships:friendshipsList){
                SyncFriendshipDTO syncFriendshipDTO = new SyncFriendshipDTO();
                syncFriendshipDTO.setFriendId(N3d.encode(friendships.getFriendId()));
                syncFriendshipDTO.setDisplayName(friendships.getDisplayName());
                syncFriendshipDTO.setStatus(friendships.getStatus());
                syncFriendshipDTO.setTimestamp(friendships.getTimestamp());
                Users u = friendships.getUsers();
                SyncUserDTO syncUserDTO = new SyncUserDTO();
                if(u!=null){
                    syncUserDTO.setId(N3d.encode(u.getId()));
                    syncUserDTO.setNickname(u.getNickname());
                    syncUserDTO.setPortraitUri(u.getPortraitUri());
                    syncUserDTO.setTimestamp(u.getTimestamp());
                }

                syncFriendshipDTO.setUser(syncUserDTO);
                syncFriendshipDTOList.add(syncFriendshipDTO);
            }
        }
        syncInfoDTO.setFriends(syncFriendshipDTOList);

        List<SyncGroupDTO> syncGroupDTOList = new ArrayList<>();
        if(!CollectionUtils.isEmpty(groupsList)){
            for(GroupMembers groupMembers:groupsList){
                SyncGroupDTO syncGroupDTO = new SyncGroupDTO();
                syncGroupDTO.setGroupId(N3d.encode(groupMembers.getGroupId()));
                syncGroupDTO.setDisplayName(groupMembers.getDisplayName());
                syncGroupDTO.setIsDeleted(GroupMembers.IS_DELETED_YES.equals(groupMembers.getIsDeleted())?true:false);
                syncGroupDTO.setRole(groupMembers.getRole());
                Groups g = groupMembers.getGroups();
                SyncGroupInnerDTO groupInnerDTO = new SyncGroupInnerDTO();
                if(g!=null){
                    groupInnerDTO.setId(N3d.encode(g.getId()));
                    groupInnerDTO.setName(g.getName());
                    groupInnerDTO.setPortraitUri(g.getPortraitUri());
                    groupInnerDTO.setTimestamp(g.getTimestamp());
                    syncGroupDTO.setGroup(groupInnerDTO);
                }
                syncGroupDTOList.add(syncGroupDTO);

            }

        }
        syncInfoDTO.setGroups(syncGroupDTOList);



        List<SyncGroupMemberDTO> syncGroupMemberDTOList = new ArrayList<>();
        if(!CollectionUtils.isEmpty(groupMembersList)){
            for(GroupMembers groupMembers:groupMembersList){
                SyncGroupMemberDTO syncGroupMemberDTO = new SyncGroupMemberDTO();
                syncGroupMemberDTO.setGroupId(N3d.encode(groupMembers.getGroupId()));
                syncGroupMemberDTO.setDisplayName(groupMembers.getDisplayName());
                syncGroupMemberDTO.setIsDeleted(GroupMembers.IS_DELETED_YES.equals(groupMembers.getIsDeleted())?true:false);
                syncGroupMemberDTO.setMemberId(N3d.encode(groupMembers.getMemberId()));
                syncGroupMemberDTO.setRole(groupMembers.getRole());
                syncGroupMemberDTO.setTimestamp(groupMembers.getTimestamp());
                Users u = groupMembers.getUsers();
                SyncUserDTO su = new SyncUserDTO();
                if(u!=null){
                    su.setId(N3d.encode(u.getId()));
                    su.setNickname(u.getNickname());
                    su.setTimestamp(u.getTimestamp());
                    su.setPortraitUri(u.getPortraitUri());
                    syncGroupMemberDTO.setUser(su);
                }
                syncGroupMemberDTOList.add(syncGroupMemberDTO);
            }
        }


        syncInfoDTO.setGroup_members(syncGroupMemberDTOList);
        return syncInfoDTO;

    }
}

