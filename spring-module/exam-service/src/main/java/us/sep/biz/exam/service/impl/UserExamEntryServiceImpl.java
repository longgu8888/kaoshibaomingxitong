package us.sep.biz.exam.service.impl;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import us.sep.base.idfactory.BizIdFactory;
import us.sep.biz.common.util.RedisLock;
import us.sep.biz.exam.enums.ExamEntryEnum;
import us.sep.biz.exam.request.UserExamEntryRequest;
import us.sep.biz.exam.service.UserExamEntryService;
import us.sep.exam.builder.UserExamEntryBO;
import us.sep.exam.entity.ExamEntryDO;
import us.sep.exam.entity.UserExamEntryDO;
import us.sep.exam.repo.ExamEntryRepo;
import us.sep.exam.repo.UserExamEntryRepo;
import us.sep.util.enums.CommonResultCode;
import us.sep.util.exceptions.CustomizeException;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
public class UserExamEntryServiceImpl implements UserExamEntryService {

    @Resource
    BizIdFactory bizIdFactory;

    @Resource
    UserExamEntryRepo userExamEntryRepo;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    ExamEntryRepo examEntryRepo;

    @Resource
    RedisLock redisLock;

    private static final String ExamEntryId = "EXAM_ENTRY_ID : ";

    

    @Override
    public List<UserExamEntryBO> getUserEntryByExamEntryId(String examEntryId) {
        List<UserExamEntryDO> userExamEntryList = userExamEntryRepo.findByExamEntryId(examEntryId);
        return userExamEntryList.stream().map(UserExamEntryDO::ToUserExamEntryBO).collect(Collectors.toList());
    }

    @Override
    public List<UserExamEntryBO> getUserEntryByUserId(String userId) {
        List<UserExamEntryDO> userExamEntryList = userExamEntryRepo.findByUserId(userId);
        return userExamEntryList.stream().map(UserExamEntryDO::ToUserExamEntryBO).collect(Collectors.toList());
    }

    @Override
    public List<UserExamEntryBO> getUserEntryByCache(String examEntryId) {
        //???????????????
        if (!examEntryRepo.existsByExamEntryId(examEntryId))
            throw new CustomizeException(CommonResultCode.UNFOUNDED, "??????????????????");

        if (!examEntryRepo.findByExamEntryId(examEntryId).get().getState().equals(ExamEntryEnum.Start.getState()))
            throw new CustomizeException(CommonResultCode.SYSTEM_ERROR, "??????????????????");

        if (!stringRedisTemplate.hasKey(ExamEntryId + examEntryId)) {
            throw new CustomizeException(CommonResultCode.UNFOUNDED, "??????????????????");
        }

        List<UserExamEntryDO> userExamEntries = new ArrayList<>();

        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(ExamEntryId + examEntryId);

        for (Object entry:entries.keySet()) {
            String userExamJson = (String) stringRedisTemplate.opsForHash().get(ExamEntryId + examEntryId , entry);
            userExamEntries.add(JSON.parseObject(userExamJson ,UserExamEntryDO.class));
        }
        return userExamEntries.stream().map(UserExamEntryDO::ToUserExamEntryBO).collect(Collectors.toList());
    }

    @Override
    public UserExamEntryBO getUserEntryById(String userEntryId) {
        Optional<UserExamEntryDO> optional = userExamEntryRepo.findByUserExamEntryId(userEntryId);
        if (!optional.isPresent())
            throw new CustomizeException(CommonResultCode.UNFOUNDED,"????????????????????????");

        return optional.get().ToUserExamEntryBO();
    }

    @Override
    //todo redis cache
    public UserExamEntryBO createUserEntry(UserExamEntryRequest request) {

        //???????????????
        if (!examEntryRepo.existsByExamEntryId(request.getExamEntryId()))
            throw new CustomizeException(CommonResultCode.UNFOUNDED, "??????????????????");

        if (!examEntryRepo.findByExamEntryId(request.getExamEntryId()).get().getState().equals(ExamEntryEnum.Start.getState()))
            throw new CustomizeException(CommonResultCode.SYSTEM_ERROR, "??????????????????");

        //??????????????????????????????
        ifUserEntryExam(request);
        try {
            //?????????????????????
            if (!stringRedisTemplate.hasKey(ExamEntryId + request.getExamEntryId())) {

                if (redisLock.setConcurrentLock(request.getExamEntryId())) {

                    UserExamEntryDO userExamEntryDO = new UserExamEntryDO();
                    BeanUtils.copyProperties(request, userExamEntryDO);
                    userExamEntryDO.setUserExamEntryId(bizIdFactory.getUserExamEntry());

                    //??????hash???
                    stringRedisTemplate.opsForHash().put(ExamEntryId + request.getExamEntryId(), request.getExamEntryId() +
                            request.getUserId(), JSON.toJSONString(userExamEntryDO));

                    redisLock.deleteConcurrentLock(request.getExamEntryId());

                    return userExamEntryDO.ToUserExamEntryBO();
                }
            }
        //????????????????????????????????????
        if (stringRedisTemplate.opsForHash().get(ExamEntryId + request.getExamEntryId(), request.getExamEntryId() +
                request.getUserId()) != null)
            throw new CustomizeException(CommonResultCode.SYSTEM_ERROR, "?????????????????????????????????");


        long number = stringRedisTemplate.opsForHash().size(ExamEntryId + request.getExamEntryId());

		
		if (redisLock.setConcurrentLock(request.getExamEntryId())) {
			
        if (number > examEntryRepo.findByExamEntryId(request.getExamEntryId()).get().getNumber()){
			 redisLock.deleteConcurrentLock(request.getExamEntryId());
            throw new CustomizeException(CommonResultCode.SYSTEM_ERROR, "??????????????????");
		}

        String entryId = request.getExamEntryId();

       

           UserExamEntryDO userExamEntryDO = new UserExamEntryDO();
           BeanUtils.copyProperties(request, userExamEntryDO);
           userExamEntryDO.setUserExamEntryId(bizIdFactory.getUserExamEntry());

           stringRedisTemplate.opsForHash().put(ExamEntryId + entryId, request.getExamEntryId() +
                   request.getUserId(), JSON.toJSONString(userExamEntryDO));

           //?????????????????? ??????????????????db
           if (stringRedisTemplate.opsForHash().size(ExamEntryId + entryId).intValue() == examEntryRepo.findByExamEntryId(entryId).get().getNumber()) {
               saveUserEntryByRedis(entryId, number);
               return null;
           }
           redisLock.deleteConcurrentLock(entryId);

           return userExamEntryDO.ToUserExamEntryBO();
       }
    }catch (InterruptedException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserExamEntryBO createUserEntryCas(UserExamEntryRequest request) {

        if (!examEntryRepo.existsByExamEntryId(request.getExamEntryId()))
            throw new CustomizeException(CommonResultCode.UNFOUNDED,"??????????????????");

        //??????????????????????????????
        ifUserEntryExam(request);

        //??????????????????
        Long start = System.currentTimeMillis();
        //?????????????????????????????????????????? 100 ????????????
        while (true) {
            //????????????????????????
            Long end = System.currentTimeMillis();
            if (start - end > 1500) {
                //??????1500ms??????(?????????????????????????????? //??????????????????)
                return null;
            }
          ExamEntryDO examEntry = examEntryRepo.findByExamEntryId(request.getExamEntryId()).get();
            if (examEntry.getNumber() > 0){
                // cas??????????????????
                int update = examEntryRepo.updateVersionAndNumber(examEntry.getVersion() ,examEntry.getExamEntryId());
                if (update == 0) {
                    return null;
                }
                UserExamEntryDO userExamEntryDO = new UserExamEntryDO();
                BeanUtils.copyProperties(request,userExamEntryDO);
                userExamEntryDO.setUserExamEntryId(bizIdFactory.getUserExamEntry());
                userExamEntryRepo.save(userExamEntryDO);
                return userExamEntryDO.ToUserExamEntryBO();
            }
            return null;
        }

    }

    @Override
    public Long getCacheRemain(String examEntryId) {
        if (!examEntryRepo.existsByExamEntryId(examEntryId))
            throw new CustomizeException(CommonResultCode.UNFOUNDED,"??????????????????");

        ExamEntryDO examEntry = examEntryRepo.findByExamEntryId(examEntryId).get();

        if (!examEntry.getState().equals(ExamEntryEnum.Start.getState()))
            throw new CustomizeException(CommonResultCode.SYSTEM_ERROR,"??????????????????");


        if (!stringRedisTemplate.hasKey(ExamEntryId + examEntryId)) {
            throw new CustomizeException(CommonResultCode.SUCCESS,"??????????????????,??????????????????");
        }

        int number = examEntry.getNumber();

        //help gc
        examEntry = null;

        //??????????????????
        return number - stringRedisTemplate.opsForHash().size(ExamEntryId + examEntryId);
    }

    @Override
    public int getDbRemain(String examEntryId) {
        if (!examEntryRepo.existsByExamEntryId(examEntryId))
            throw new CustomizeException(CommonResultCode.UNFOUNDED,"??????????????????");

        ExamEntryDO examEntry = examEntryRepo.findByExamEntryId(examEntryId).get();
        if (!examEntry.getState().equals(ExamEntryEnum.Start.getState()))
            throw new CustomizeException(CommonResultCode.SYSTEM_ERROR,"??????????????????");

        int number = examEntry.getNumber();

        //help gc
        examEntry = null;


        return number;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<UserExamEntryBO> deleteUserEntryByExamEntry(String examEntryId) {
        if (!userExamEntryRepo.existsByExamEntryId(examEntryId))
            throw new CustomizeException(CommonResultCode.UNFOUNDED,"??????????????????????????????");

      List<UserExamEntryBO> userExamEntryList = userExamEntryRepo.findByExamEntryId(examEntryId)
              .stream().map(UserExamEntryDO::ToUserExamEntryBO).collect(Collectors.toList());
      userExamEntryRepo.deleteByExamEntryId(examEntryId);
        return userExamEntryList;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<UserExamEntryBO> deleteUserEntryByUserId(String userId) {
        if (!userExamEntryRepo.existsByUserId(userId))
            throw new CustomizeException(CommonResultCode.UNFOUNDED,"???????????????????????????????????????");
        List<UserExamEntryBO> userExamEntryList = userExamEntryRepo.findByUserId(userId)
                .stream().map(UserExamEntryDO::ToUserExamEntryBO).collect(Collectors.toList());
        userExamEntryRepo.deleteByUserId(userId);
        return userExamEntryList;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserExamEntryBO deleteUserEntryById(String userEntryId) {
        if (!userExamEntryRepo.existsByUserExamEntryId(userEntryId))
            throw new CustomizeException(CommonResultCode.UNFOUNDED,"????????????????????????");

        return userExamEntryRepo.findByUserExamEntryId(userEntryId).get().ToUserExamEntryBO();
    }

    @Async
    @Transactional(rollbackFor = Exception.class)
    void saveUserEntryByRedis(String entryId, Long number){
        //lock.lock();
        //????????????????????????

        //?????????db???
        List<UserExamEntryDO> userExamEntries = new ArrayList<>();

        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(ExamEntryId + entryId);

        for (Object entry:entries.keySet()) {
            String userExamJson = (String) stringRedisTemplate.opsForHash().get(ExamEntryId + entryId , entry);
            userExamEntries.add(JSON.parseObject(userExamJson ,UserExamEntryDO.class));
        }
        stringRedisTemplate.delete(ExamEntryId + entryId);

        userExamEntryRepo.saveAll(userExamEntries);

        ExamEntryDO examEntryDO = examEntryRepo.findByExamEntryId(entryId).get();
        examEntryDO.setNumber(0);
        examEntryDO.setState(ExamEntryEnum.Finish.getState());
        examEntryDO.setVersion(0L);
        examEntryRepo.save(examEntryDO);

        //help gc
        userExamEntries = null;
        entryId = null;

        //lock.unlock();

    }

    private void ifUserEntryExam(UserExamEntryRequest request){
       if (userExamEntryRepo.existsByUserIdAndExamEntryId(request.getUserId(),request.getExamEntryId()))
           throw new CustomizeException(CommonResultCode.SYSTEM_ERROR,"?????????????????????????????????");
    }
}
